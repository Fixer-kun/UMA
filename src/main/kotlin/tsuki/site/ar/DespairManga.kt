package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.util.generateUid

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

import java.time.Instant
import java.util.EnumSet

@MangaSourceParser("DESPAIRMANGA", "Despair Manga", "ar")
internal class DespairManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DESPAIRMANGA, 24) {

    override val configKeyDomain = ConfigKey.Domain("despair-world.com")

    private val apiUrl get() = "https://$domain/supabase-api/rest/v1"

    private val apiHeaders: Headers by lazy {
        Headers.Builder()
            .add("apikey", ANON_KEY)
            .add("Authorization", "Bearer $ANON_KEY")
            .add("User-Agent", config[userAgentKey])
            .build()
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.POPULARITY -> "views_count.desc"
            else -> "updated_at.desc"
        }

        val urlBuilder = "$apiUrl/manga_works".toHttpUrl().newBuilder()
            .addQueryParameter("select", WORK_COLUMNS)
            .addQueryParameter("order", sort)
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())

        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val term = raw.replace("\\", "\\\\").replace("\"", "\\\"")
            urlBuilder.addQueryParameter(
                "or",
                "(title_ar.ilike.\"*$term*\",title_en.ilike.\"*$term*\")",
            )
        }

        val array = fetchArray(urlBuilder.build())
        return (0 until array.length()).mapNotNull { toManga(array.optJSONObject(it)) }
    }

    private fun toManga(obj: JSONObject?): Manga? {
        obj ?: return null
        val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val titleAr = obj.optString("title_ar").takeIf { it.isNotBlank() }
        val titleEn = obj.optString("title_en").takeIf { it.isNotBlank() }
        val title = titleAr ?: titleEn ?: return null
        val cover = obj.optString("cover_url").takeIf { it.isNotBlank() }

        return Manga(
            id = generateUid(slug),
            url = "/series/$slug",
            publicUrl = "https://$domain/series/$slug",
            title = title,
            altTitles = setOfNotNull(titleEn?.takeIf { it != title }),
            coverUrl = cover,
            rating = RATING_UNKNOWN,
            contentRating = null,
            tags = emptySet(),
            state = stateOf(obj.optString("status")),
            authors = setOfNotNull(
                obj.optString("author_or_studio").takeIf { it.isNotBlank() },
            ),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.trimEnd('/').substringAfterLast('/')
        val work = getWork(slug) ?: throw Exception("Series not found: $slug")
        val workId = work.optString("id").takeIf { it.isNotBlank() }
            ?: throw Exception("Missing work id for $slug")

        val cover = work.optString("cover_url").takeIf { it.isNotBlank() }
        val titleAr = work.optString("title_ar").takeIf { it.isNotBlank() }
        val titleEn = work.optString("title_en").takeIf { it.isNotBlank() }
        val title = titleAr ?: titleEn ?: manga.title

        return manga.copy(
            title = title,
            altTitles = manga.altTitles + setOfNotNull(titleEn?.takeIf { it != title }),
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = setOfNotNull(
                work.optString("author_or_studio").takeIf { it.isNotBlank() },
            ),
            state = stateOf(work.optString("status")),
            tags = parseGenres(work.optJSONArray("genres")),
            description = buildDescription(work),
            chapters = getChapters(workId, slug),
        )
    }

    private suspend fun getWork(slug: String): JSONObject? {
        val url = "$apiUrl/manga_works".toHttpUrl().newBuilder()
            .addQueryParameter("select", WORK_COLUMNS)
            .addQueryParameter("slug", "eq.$slug")
            .addQueryParameter("limit", "1")
            .build()
        val array = fetchArray(url)
        return if (array.length() > 0) array.optJSONObject(0) else null
    }

    private suspend fun getChapters(workId: String, slug: String): List<MangaChapter> {
        val all = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val url = "$apiUrl/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("select", CHAPTER_COLUMNS)
                .addQueryParameter("work_id", "eq.$workId")
                .addQueryParameter("order", "chapter_number.desc,id.desc")
                 //.addQueryParameter("limit", CHAPTER_CHUNK.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
            val array = fetchArray(url)
            val chunk = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            all += chunk
            if (chunk.size < CHAPTER_CHUNK) break
            offset += chunk.size
        }
        return all.mapNotNull { toChapter(it, slug) }.reversed()
    }

    private fun toChapter(obj: JSONObject, slug: String): MangaChapter? {
        val chapterId = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val number = obj.optDouble("chapter_number", Double.NaN)
        if (number.isNaN()) return null
        val numStr = number.toString().removeSuffix(".0")

        val isFree = obj.optBoolean("is_free", true)
        val rawTitle = obj.optString("title").takeIf { it.isNotBlank() }
        val baseName = rawTitle ?: "الفصل $numStr"
        val displayTitle = if (!isFree) "🔒 $baseName" else baseName

        return MangaChapter(
            id = generateUid(chapterId),
            url = "/series/$slug/main/ch/$numStr#chapterId=$chapterId",
            title = displayTitle,
            number = number.toFloat(),
            volume = 0,
            branch = null,
            uploadDate = parseIsoDate(obj.optString("created_at").takeIf { it.isNotBlank() }),
            scanlator = null,
            source = source,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfter("#chapterId=", "")
            .takeIf { it.isNotBlank() }
            ?: return emptyList()

        val url = "$apiUrl/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("select", "pages")
            .addQueryParameter("id", "eq.$chapterId")
            .build()

        val array = fetchArray(url)
        if (array.length() == 0) return emptyList()
        val pagesArr = array.optJSONObject(0)?.optJSONArray("pages") ?: return emptyList()

        return (0 until pagesArr.length()).mapNotNull { i ->
            val img = pagesArr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaPage(
                id = generateUid(img),
                url = img,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchArray(url: HttpUrl): JSONArray = webClient.httpGet(url, apiHeaders).use { JSONArray(it.body.string()) }

    private fun stateOf(status: String): MangaState? = when (status) {
        "مستمر" -> MangaState.ONGOING
        "مكتمل" -> MangaState.FINISHED
        else -> null
    }

    private fun parseGenres(array: JSONArray?): Set<MangaTag> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNull { i ->
            val name = array.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaTag(key = name, title = name, source = source)
        }.toSet()
    }

    private fun buildDescription(work: JSONObject): String? {
        val synopsis = work.optString("synopsis").takeIf { it.isNotBlank() }
        val titleEn = work.optString("title_en").takeIf { it.isNotBlank() }
        val titleAr = work.optString("title_ar").takeIf { it.isNotBlank() }
        val mainTitle = titleAr ?: titleEn

        return buildString {
            synopsis?.let(::append)
            if (!titleEn.isNullOrBlank() && titleEn != mainTitle) {
                if (isNotEmpty()) append("\n\n")
                append("أسماء أخرى:\n")
                append(titleEn)
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun parseIsoDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
    }

    companion object {
        private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzkwMzczMDE4LCJleHAiOjE5NDgwNTMwMTh9.xYgAlC_9SuUCdzdff6wBYGWzYFmAI9apP_mk4DCPaVI"

        private const val WORK_COLUMNS = "id,slug,title_ar,title_en,cover_url,genres,synopsis,status,author_or_studio"

        private const val CHAPTER_COLUMNS = "id,chapter_number,title,is_free,created_at"

        private const val CHAPTER_CHUNK = 1000
    }
}
