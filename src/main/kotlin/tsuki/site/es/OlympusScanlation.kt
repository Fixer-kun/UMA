package tsuki.site.es

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.OkHttpWebClient

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
import tsuki.util.parseJson

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@MangaSourceParser("OLYMPUSSCANLATION", "Olympus Scanlation", "es")
internal class OlympusScanlation(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.OLYMPUSSCANLATION, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("olympusxyz.com")

    private val baseUrl get() = "https://$domain"
    private val apiBaseUrl get() = "https://panel.$domain"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fallbackDateFormats = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val webClient by lazy {
        OkHttpWebClient(context.httpClient.newBuilder().build(), source)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "application/json")
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    @Volatile
    private var seriesList: List<MangaDto> = emptyList()
    @Volatile
    private var lastFetchTime: Long = 0L
    private val seriesMutex = Mutex()
    private val slugMap = ConcurrentHashMap<String, String>()
    private val genreById = ConcurrentHashMap<String, List<String>>()

    @Volatile
    private var genresCache: Set<MangaTag> = emptySet()

    private suspend fun fetchSeriesListIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && seriesList.isNotEmpty() && (now - lastFetchTime) < TimeUnit.HOURS.toMillis(1)) return
        seriesMutex.withLock {
            if (!force && seriesList.isNotEmpty() && (now - lastFetchTime) < TimeUnit.HOURS.toMillis(1)) return
            val urls = listOf("$baseUrl/api/series/list", "$apiBaseUrl/api/series/list")
            for (url in urls) {
                val dataArray = runCatching {
                    val json = webClient.httpGet(url, getRequestHeaders()).parseJson()
                    json.optJSONArray("data")
                        ?: json.optJSONObject("payload")?.optJSONArray("data")
                        ?: json.optJSONObject("result")?.optJSONArray("data")
                }.getOrNull() ?: continue
                val comics = mutableListOf<MangaDto>()
                val genres = mutableSetOf<String>()
                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.optJSONObject(i) ?: continue
                    if (obj.optString("type") != "comic") continue
                    val id = obj.optInt("id", -1)
                    if (id <= 0) continue
                    val name = obj.optString("name", "")
                    val slug = obj.optString("slug", "")
                    if (slug.isBlank()) continue
                    val cover = obj.optString("cover", "")
                    val summary = obj.optString("summary", "")
                    val statusId = obj.optJSONObject("status")?.optInt("id") ?: -1
                    val seriesGenres = mutableListOf<String>()
                    obj.optJSONArray("genres")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            arr.optJSONObject(j)?.optString("name", "")
                                ?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    seriesGenres.add(it)
                                    genres.add(it)
                                }
                        }
                    }
                    comics.add(MangaDto(id, name, slug, cover, summary, statusId, seriesGenres))
                    slugMap[id.toString()] = slug
                    genreById[id.toString()] = seriesGenres
                }
                if (comics.isNotEmpty()) {
                    seriesList = comics
                    genresCache = genres.map { MangaTag(it.lowercase(), it, source) }.toSet()
                    lastFetchTime = now
                    return
                }
            }
        }
    }

    private data class MangaDto(
        val id: Int, val name: String, val slug: String, val cover: String,
        val summary: String, val statusId: Int, val genres: List<String>
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        fetchSeriesListIfNeeded()
        return MangaListFilterOptions(
            availableTags = genresCache,
            availableStates = EnumSet.of(
                MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED,
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        fetchSeriesListIfNeeded()

        val query = filter.query?.trim().orEmpty()
        val genreFilter = filter.tags.map { it.title.lowercase() }.toSet()

        val baseList: List<Manga> = if (query.isNotEmpty()) {
            val filtered = seriesList.filter { dto ->
                dto.name.contains(query, ignoreCase = true) &&
                        (genreFilter.isEmpty() || dto.genres.any { it.lowercase() in genreFilter })
            }
            val start = (page - 1) * pageSize
            if (start >= filtered.size) return emptyList()
            val end = (start + pageSize).coerceAtMost(filtered.size)
            filtered.subList(start, end).map { it.toManga() }
        } else {
            val apiUrl = when (order) {
                SortOrder.POPULARITY -> "$baseUrl/api/rankings?page=$page&period=total_ranking"
                SortOrder.UPDATED -> "$baseUrl/api/new-chapters?page=$page"
                else -> "$baseUrl/api/rankings?page=$page&period=total_ranking"
            }

            val json = webClient.httpGet(apiUrl, getRequestHeaders()).parseJson()
            val dataArray = json.optJSONArray("data") ?: return emptyList()
            val mangas = mutableListOf<Manga>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.optJSONObject(i) ?: continue
                if (obj.optString("type") != "comic") continue
                val id = obj.optInt("id", -1)
                if (id <= 0) continue
                val name = obj.optString("name", "")
                val slug = obj.optString("slug", "")
                val cover = obj.optString("cover", "")
                if (slug.isNotBlank()) slugMap[id.toString()] = slug
                mangas.add(MangaDto(id, name, slug, cover, "", -1, emptyList()).toManga())
            }
            mangas
        }

        if (genreFilter.isEmpty()) return baseList

        return baseList.filter { m ->
            genreById[m.url]?.any { it.lowercase() in genreFilter } == true
        }
    }

    private fun MangaDto.toManga() = Manga(
        id = generateUid(id.toString()),
        url = id.toString(),
        publicUrl = "$baseUrl/series/comic-$slug",
        title = name,
        coverUrl = cover.takeIf { it.isNotEmpty() }?.let {
            if (it.startsWith("http")) it else "$baseUrl/$it"
        } ?: "",
        altTitles = emptySet(),
        rating = RATING_UNKNOWN,
        contentRating = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
    )

    override suspend fun getDetails(manga: Manga): Manga {
        fetchSeriesListIfNeeded()
        val mangaId = manga.url

        var slug = slugMap[mangaId]
        if (slug == null) {
            fetchSeriesListIfNeeded(force = true)
            slug = slugMap[mangaId]
        }
        if (slug == null) return manga.copy(chapters = emptyList())

        val url = "$baseUrl/api/series/$slug?type=comic"
        val json = runCatching { webClient.httpGet(url, getRequestHeaders()).parseJson() }.getOrNull()
            ?: return manga
        val data = json.optJSONObject("data") ?: return manga

        val title = data.optString("name", manga.title)
        val cover = data.optString("cover", manga.coverUrl)
        val summary = data.optString("summary", "")
        val statusId = data.optJSONObject("status")?.optInt("id") ?: -1
        val state = when (statusId) {
            1 -> MangaState.ONGOING
            3 -> MangaState.PAUSED
            4 -> MangaState.FINISHED
            5 -> MangaState.ABANDONED
            else -> null
        }
        val genres = mutableSetOf<MangaTag>()
        data.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name", "")?.takeIf { it.isNotEmpty() }?.let {
                    genres.add(MangaTag(it.lowercase(), it, source))
                }
            }
        }
        val chapters = fetchChapters(mangaId, slug)

        return manga.copy(
            title = title,
            coverUrl = cover?.takeIf { it.isNotEmpty() }?.let {
                if (it.startsWith("http")) it else "$baseUrl/$it"
            } ?: manga.coverUrl,
            description = summary,
            tags = genres,
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun fetchChapters(mangaId: String, slug: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        var total = Int.MAX_VALUE
        var consecutiveEmpty = 0
        while (chapters.size < total) {
            val json = tryFetchChapterPage(slug, page)
            if (json == null) {
                if (chapters.isNotEmpty()) break
                delay(500.milliseconds)
                val retry = tryFetchChapterPage(slug, page) ?: break
                consumeChapterPage(retry, mangaId, slug, chapters)
                val retryData = retry.optJSONArray("data") ?: JSONArray()
                if (retryData.length() == 0) break
                total = retry.optJSONObject("meta")?.optInt("total", retryData.length()) ?: retryData.length()
                page++
                continue
            }
            val dataArray = json.optJSONArray("data") ?: JSONArray()
            if (dataArray.length() == 0) {
                if (++consecutiveEmpty >= 2) break
                delay(500.milliseconds)
                page++
                continue
            }
            consecutiveEmpty = 0
            consumeChapterPage(json, mangaId, slug, chapters)
            total = json.optJSONObject("meta")?.optInt("total", dataArray.length()) ?: dataArray.length()
            page++
        }
        return chapters.sortedBy { it.number }
    }

    private fun consumeChapterPage(
        json: JSONObject,
        mangaId: String,
        slug: String,
        out: MutableList<MangaChapter>,
    ) {
        val dataArray = json.optJSONArray("data") ?: return
        for (i in 0 until dataArray.length()) {
            val ch = dataArray.optJSONObject(i) ?: continue
            val id = ch.optInt("id", -1)
            if (id <= 0) continue
            val name = ch.optString("name", "").trim()
            if (name.isBlank()) continue
            val publishedAt = ch.optString("published_at", "")
            val uploadDate = parseDate(publishedAt)
            out.add(
                MangaChapter(
                    id = generateUid("$mangaId/$id"),
                    title = "Capitulo $name",
                    number = name.toFloatOrNull() ?: 0f,
                    volume = 0,
                    url = "/series/comic-$slug/capitulo/$name",
                    uploadDate = uploadDate,
                    scanlator = null,
                    branch = null,
                    source = source,
                )
            )
        }
    }

    private fun parseDate(str: String): Long {
        if (str.isBlank()) return 0L
        runCatching { dateFormat.parse(str)?.time }.getOrNull()?.let { return it }
        for (fmt in fallbackDateFormats) {
            runCatching { fmt.parse(str)?.time }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private suspend fun tryFetchChapterPage(slug: String, page: Int): JSONObject? {
        val urls = listOf(
            "$apiBaseUrl/api/series/$slug/chapters?page=$page&direction=desc&type=comic",
            "$baseUrl/api/series/$slug/chapters?page=$page&direction=desc&type=comic",
        )
        for (url in urls) {
            val parsed = runCatching { webClient.httpGet(url, getRequestHeaders()).parseJson() }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        /** chapter.url is /series/comic-{slug}/capitulo/{chapterName} */
        val segments = chapter.url.split("/").filter { it.isNotEmpty() }
        if (segments.size < 4) return emptyList()

        val slugWithPrefix = segments[1]
        val chapterName = segments[3]
        val slug = slugWithPrefix.removePrefix("comic-")

        val apiUrl = "$baseUrl/api/capitulo/$slug/$chapterName"
        val json = runCatching {
            webClient.httpGet(apiUrl, getRequestHeaders()).parseJson()
        }.getOrNull() ?: return emptyList()

        val pagesArray = json.optJSONObject("chapter")?.optJSONArray("pages")
            ?: return emptyList()

        return (0 until pagesArray.length()).mapNotNull { i ->
            val url = pagesArray.optString(i).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
