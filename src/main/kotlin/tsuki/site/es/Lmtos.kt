package tsuki.site.es

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.NextJsData
import tsuki.util.urlEncoded

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("LMTOS", "Lmtos", "es")
internal class Lmtos(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.LMTOS, 20) {

    override val configKeyDomain = ConfigKey.Domain("lmtos.net")
    private val baseUrl = "https://$domain"

    @Volatile
    private var mangaCache: List<MangaDto>? = null

    @Volatile
    private var cacheTimestamp = 0L

    private val cacheDuration = 10 * 60 * 1000L

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .set("User-Agent", config[userAgentKey])
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )


    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRES.map { genre ->
            MangaTag(key = genre, title = genre, source = source)
        }.toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHUA,
            ContentType.MANHWA,
            ContentType.ONE_SHOT,
        ),
    )

    private suspend fun fetchSeriesPage(
        page: Int,
        sort: String,
        query: String,
        genres: List<String>,
        status: String?,
        type: String?,
        contentRating: ContentRating?,
    ): PageResult {
        val url = buildString {
            append("$baseUrl/series")
            val params = mutableListOf<String>()
            params += "page=$page"
            params += "sort=$sort"
            if (query.isNotBlank()) params += "q=${query.urlEncoded()}"
            genres.forEach { params += "genre=${it.urlEncoded()}" }
            status?.let { params += "status=$it" }
            type?.let { params += "type=$it" }
            contentRating?.let {
                when (it) {
                    ContentRating.ADULT -> params += "adult=adult"
                    ContentRating.SAFE -> params += "adult=safe"
                    else -> {}
                }
            }
            if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
        }

        val html = webClient.httpGet(url).body.string()

        val root = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("mangas") && node.has("total") && node.has("pageSize")
        } as? JSONObject ?: throw Exception("Could not find paginated series payload")

        val mangasArray = root.getJSONArray("mangas")
        val list = ArrayList<MangaDto>(mangasArray.length())
        for (i in 0 until mangasArray.length()) {
            list += MangaDto.fromJson(mangasArray.getJSONObject(i))
        }

        return PageResult(
            mangas = list,
            total = root.optInt("total", 0),
            pageSize = root.optInt("pageSize", pageSize),
        )
    }

    private data class PageResult(
        val mangas: List<MangaDto>,
        val total: Int,
        val pageSize: Int,
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val sortParam = when (order) {
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.UPDATED -> "recent"
            SortOrder.POPULARITY -> "rating"
            else -> "title"
        }

        val query = filter.query?.trim().orEmpty()
        val genres = filter.tags.map { it.key }
        val state = filter.states.firstOrNull()
        val type = filter.types.firstOrNull()
        val rating = filter.contentRating.firstOrNull()

        val statusParam = when (state) {
            MangaState.ONGOING -> "ongoing"
            MangaState.FINISHED -> "completed"
            MangaState.PAUSED -> "paused"
            else -> null
        }
        val typeParam = when (type) {
            ContentType.MANGA -> "manga"
            ContentType.MANHUA -> "manhua"
            ContentType.MANHWA -> "manhwa"
            ContentType.ONE_SHOT -> "oneshot"
            else -> null
        }

        val result = fetchSeriesPage(
            page = page,
            sort = sortParam,
            query = query,
            genres = genres,
            status = statusParam,
            type = typeParam,
            contentRating = rating,
        )
        return result.mangas.map { it.toManga(baseUrl, source) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val html = webClient.httpGet(manga.publicUrl).body.string()

        val mangaObj = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("slug") && node.has("title") && node.has("genres")
        } as? JSONObject ?: throw Exception("Could not find 'manga' object")

        val chaptersArray = NextJsData.findArray(html) { first ->
            first.has("slug") && first.has("number")
        } ?: JSONArray()

        val dto = MangaDto.fromJson(mangaObj)
        val mangaSlug = manga.url.substringAfterLast("/")

        val chapters = ArrayList<MangaChapter>(chaptersArray.length())
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.getJSONObject(i)
            val slug = ch.optString("slug")
            val chNumber = ch.optDouble("number", -1.0).toFloat()
            val chHref = "/manga/$mangaSlug/$slug"
            chapters += MangaChapter(
                id = generateUid(source, chHref),
                url = chHref,
                title = "Ch. ${chNumber.toString().removeSuffix(".0")}",
                number = chNumber,
                volume = 0,
                uploadDate = parseDate(ch.optString("createdAt")),
                source = source,
                scanlator = null,
                branch = null,
            )
        }
        chapters.sortBy { it.number }

        return dto.toManga(baseUrl, source).copy(
            chapters = chapters,
            description = dto.description ?: manga.description,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val html = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).body.string()

        val chapterObj = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("pages")
        } as? JSONObject ?: throw Exception("Could not find 'chapter' object")

        val pagesArray = chapterObj.optJSONArray("pages") ?: JSONArray()
        return (0 until pagesArray.length()).map { i ->
            val pageUrl = pagesArray.getString(i)
            MangaPage(
                id = generateUid(source, pageUrl),
                url = pageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching { createDateFormat().parse(s)?.time ?: 0L }.getOrDefault(0L)
    }

    private data class MangaDto(
        val slug: String,
        val title: String,
        val alternativeTitles: List<String>? = null,
        val description: String? = null,
        val coverImage: String? = null,
        val isAdult: Boolean = false,
        val type: String? = null,
        val status: String? = null,
        val demographic: String? = null,
        val genres: List<String>? = null,
        val author: String? = null,
        val artist: String? = null,
        val latestChapterCreatedAt: Long? = null,
        val totalViews: Int? = null,
    ) {
        fun toManga(baseUrl: String, source: MangaSource): Manga {
            val path = "/manga/$slug"
            val tags = genres?.mapTo(LinkedHashSet()) { g ->
                MangaTag(key = g.lowercase(), title = g, source = source)
            }.orEmpty()
            val state = when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "paused" -> MangaState.PAUSED
                else -> null
            }
            return Manga(
                id = generateUid(source, path),
                url = path,
                publicUrl = "$baseUrl$path",
                title = title,
                altTitles = alternativeTitles.orEmpty().toSet(),
                coverUrl = coverImage?.takeIf { it.isNotEmpty() }?.let {
                    if (it.startsWith("http")) it else "$baseUrl/$it"
                } ?: "",
                rating = RATING_UNKNOWN,
                contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
                tags = tags,
                state = state,
                authors = setOfNotNull(author, artist),
                source = source,
            )
        }

        companion object {
            fun fromJson(obj: JSONObject): MangaDto {
                val alt = obj.optJSONArray("alternativeTitles")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                }
                val gen = obj.optJSONArray("genres")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                }
                val latest = obj.optString("latestChapterCreatedAt")
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { createDateFormat().parse(it)?.time }.getOrNull() }
                return MangaDto(
                    slug = obj.optString("slug"),
                    title = obj.optString("title"),
                    alternativeTitles = alt,
                    description = obj.optString("description").takeIf { it.isNotEmpty() },
                    coverImage = obj.optString("coverImage").takeIf { it.isNotEmpty() },
                    isAdult = obj.optBoolean("isAdult", false),
                    type = obj.optString("type").takeIf { it.isNotEmpty() },
                    status = obj.optString("status").takeIf { it.isNotEmpty() },
                    demographic = obj.optString("demographic").takeIf { it.isNotEmpty() },
                    genres = gen,
                    author = obj.optString("author").takeIf { it.isNotEmpty() },
                    artist = obj.optString("artist").takeIf { it.isNotEmpty() },
                    latestChapterCreatedAt = latest,
                    totalViews = obj.optInt("totalViews").takeIf { it > 0 },
                )
            }
        }
    }

    companion object {
        private fun createDateFormat(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        private val GENRES = listOf(
            "Acción", "Artes Marciales", "Aventuras", "Carreras", "Ciencia Ficción",
            "Comedia", "Demencia", "Demonios", "Deportes", "Drama", "Ecchi",
            "Escolares", "Gore", "Harem", "Isekai", "Juegos", "Magia", "Mecha",
            "Militar", "Misterio", "Música", "Parodia", "Policía", "Psicológico",
            "Recuentos de la vida", "Romance", "Romcom", "Samurai", "Sobrenatural",
            "Superpoderes", "Suspenso", "Terror", "Vampiros", "Yaoi", "Yuri",
        )
    }
}
