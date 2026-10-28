package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentType
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

import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseJson

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

import java.time.Instant
import java.util.EnumSet

@MangaSourceParser("TOPMANHUA", "ManhuaTop", "en", ContentType.MANHUA)
internal class TopManhua(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.TOPMANHUA, 20) {

    override val configKeyDomain = ConfigKey.Domain("manhuatop.org")

    private val baseUrl get() = "https://$domain"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.POPULARITY_YEAR,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = tryFetchGenres()
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
        )
    }

    private suspend fun tryFetchGenres(): Set<MangaTag> = try {
        val all = mutableListOf<JSONObject>()
        var page = 1
        while (true) {
            val url = "$baseUrl/api/genres".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .build()
            val json = webClient.httpGet(url).parseJson()
            val terms = json.optJSONArray("terms") ?: break
            for (i in 0 until terms.length()) {
                terms.optJSONObject(i)?.let { all += it }
            }
            if (!json.optBoolean("hasMore", false)) break
            page++
        }
        all.filter { it.optInt("count", 0) > 0 }
            .mapNotNull { t ->
                val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = t.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MangaTag(key = id, title = name, source = source)
            }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        if (query.length == 1) {
            throw Exception("Search query must be at least 2 characters")
        }

        val listing = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addPathSegment("search").addPathSegment(query).addPathSegment("")
                if (page > 1) addQueryParameter("page", page.toString())
            } else {
                addPathSegment("series").addPathSegment("")
                if (page > 1) {
                    addPathSegment("page").addPathSegment(page.toString()).addPathSegment("")
                }
            }

            val sort = when (order) {
                SortOrder.POPULARITY -> "views"
                SortOrder.POPULARITY_WEEK -> "trending-week"
                SortOrder.POPULARITY_MONTH -> "trending-month"
                SortOrder.POPULARITY_YEAR -> "trending-year"
                SortOrder.UPDATED -> "latest_update"
                SortOrder.NEWEST -> "newest"
                SortOrder.ALPHABETICAL -> "title"
                SortOrder.RATING -> "rating"
                else -> "latest_update"
            }
            if (sort.isNotEmpty()) addQueryParameter("sort", sort)

            filter.types.firstOrNull()?.let {
                val t = when (it) {
                    ContentType.MANHWA -> "Manhwa"
                    ContentType.MANGA -> "Manga"
                    ContentType.MANHUA -> "Manhua"
                    else -> null
                }
                if (t != null) addQueryParameter("manga_type", t)
            }

            filter.states.firstOrNull()?.let {
                val s = when (it) {
                    MangaState.ONGOING -> "on-going"
                    MangaState.FINISHED -> "end"
                    MangaState.PAUSED -> "on-hold"
                    MangaState.ABANDONED -> "canceled"
                    else -> null
                }
                if (s != null) addQueryParameter("status", s)
            }

            if (filter.tags.isNotEmpty()) {
                val ids = filter.tags.take(10).joinToString(",") { it.key }
                addQueryParameter("genre", ids)
            }
        }.build()

        val json = webClient.httpGet(apiUrl("discovery", listing)).parseJson()
        val items = json.optJSONArray("items") ?: return emptyList()

        return (0 until items.length()).mapNotNull { parseCard(items.optJSONObject(it)) }
    }

    private fun parseCard(obj: JSONObject?): Manga? {
        obj ?: return null
        val path = obj.optString("path").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val thumb = obj.optString("thumbnail").takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else baseUrl + it }
        val state = when (obj.optString("status")) {
            "on-going" -> MangaState.ONGOING
            "end" -> MangaState.FINISHED
            "on-hold" -> MangaState.PAUSED
            "canceled" -> MangaState.ABANDONED
            else -> null
        }
        val rating = obj.optDouble("rating", 0.0)
            .toFloat()
            .div(5f)
            .takeIf { it > 0f && it <= 1f }
            ?: RATING_UNKNOWN

        return Manga(
            id = generateUid(path),
            url = path,
            publicUrl = baseUrl + path,
            title = title,
            altTitles = emptySet(),
            coverUrl = thumb,
            rating = rating,
            contentRating = null,
            tags = emptySet(),
            state = state,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaPath = if (manga.url.startsWith("/")) manga.url else "/manhua/${manga.url.trimEnd('/')}/"
        val doc = webClient.httpGet((baseUrl + mangaPath).toHttpUrl()).parseHtml()

        val title = doc.selectFirst("h1#manga-title")?.text()?.trim() ?: manga.title
        val cover = doc.selectFirst(".detail-cover img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else baseUrl + it }

        val author = doc.select(".metadata-list dt:matchesOwn(^Author$) + dd a").eachText()
            .filterNot { it == "Updating" }.joinToString(", ").takeIf { it.isNotBlank() }
        val artist = doc.select(".metadata-list dt:matchesOwn(^Artist$) + dd a").eachText()
            .filterNot { it == "Updating" }.joinToString(", ").takeIf { it.isNotBlank() }

        val typeMeta = doc.select(".metadata-list dt:matchesOwn(^Type$) + dd a").eachText()
            .filterNot { it == "Updating" }
        val genreLinks = doc.select(".genre-chips a, .tags-chips a").eachText()
        val allGenres = (typeMeta + genreLinks).distinctBy { it.lowercase() }

        val state = when (doc.selectFirst(".detail-stats .status-badge")?.text()?.lowercase()?.trim()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "on hold" -> MangaState.PAUSED
            "canceled" -> MangaState.ABANDONED
            else -> null
        }

        val ratingValue = doc.selectFirst(".detail-stats .card-rating")?.text()?.trim()
            ?.substringBefore(" ")
            ?.trim()
            ?.toFloatOrNull()
            ?.div(5f)
            ?.takeIf { it > 0f && it <= 1f }

        val altTitlesSet = doc.selectFirst("p.alternative-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.split(" / ")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()

        val description = doc.selectFirst(".rich-content.synopsis")?.wholeText()
            ?.lines()?.joinToString("\n") { it.trim() }
            ?.replace(Regex("""\n{3,}"""), "\n\n")
            ?.trim()?.takeIf { it.isNotEmpty() }

        val tags = allGenres.map { MangaTag(key = it, title = it, source = source) }.toSet()
        val chapters = fetchChapters(mangaPath)

        return manga.copy(
            title = title,
            url = mangaPath,
            publicUrl = baseUrl + mangaPath,
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = setOfNotNull(author, artist),
            description = description,
            state = state,
            tags = tags,
            rating = ratingValue ?: manga.rating,
            altTitles = altTitlesSet,
            chapters = chapters,
        )
    }

    private suspend fun fetchChapters(mangaPath: String): List<MangaChapter> {
        val first = fetchChapterPage(mangaPath, 1)
        val totalPages = (first.total + CHAPTERS_PER_PAGE - 1) / CHAPTERS_PER_PAGE
        val all = mutableListOf<JSONObject>()
        all += first.items
        for (p in 2..totalPages) {
            all += fetchChapterPage(mangaPath, p).items
        }

        return all
            .filter { it.optString("path").takeIf { p -> p.isNotBlank() } != null }
            .distinctBy { it.optString("path") }
            .mapNotNull { toChapter(it, mangaPath) }
            .sortedBy { it.number }
    }

    private suspend fun fetchChapterPage(mangaPath: String, page: Int): ChapterPage {
        val series = (baseUrl + mangaPath).toHttpUrl().newBuilder()
            .addQueryParameter("tab", "chapters")
            .addQueryParameter("chapter_page", page.toString())
            .build()
        val json = webClient.httpGet(apiUrl("reader", series, "chapters")).parseJson()
        val items = json.optJSONArray("items")
        val list = if (items != null) (0 until items.length()).mapNotNull { items.optJSONObject(it) } else emptyList()
        return ChapterPage(list, json.optInt("total", 0))
    }

    private fun toChapter(obj: JSONObject, mangaPath: String): MangaChapter? {
        val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: slug
        val extra = obj.optString("extra").takeIf { it.isNotBlank() }
        val date = obj.optString("date").takeIf { it.isNotBlank() }

        val name = listOfNotNull(title, extra).joinToString(" - ")
        val chapterPath = "${mangaPath.trimEnd('/')}/$slug/"

        return MangaChapter(
            id = generateUid(chapterPath),
            url = chapterPath,
            title = name,
            number = name.extractChapterNumber(),
            volume = 0,
            branch = null,
            uploadDate = parseIsoDate(date),
            scanlator = null,
            source = source,
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterPath = if (chapter.url.startsWith("/")) chapter.url else "/${chapter.url}"
        val chapterUrl = (baseUrl + chapterPath).toHttpUrl()

        val images = mutableListOf<String>()
        var page = 1
        while (true) {
            val contentUrl = chapterUrl.newBuilder().apply {
                if (page > 1) addQueryParameter("content_page", page.toString())
            }.build()
            val json = webClient.httpGet(apiUrl("reader", contentUrl, "content")).parseJson()
            val arr = json.optJSONArray("images") ?: break
            for (i in 0 until arr.length()) {
                val imgUrl = arr.optJSONObject(i)?.optString("url").orEmpty()
                if (imgUrl.isNotBlank()) images += imgUrl
            }
            if (!json.optBoolean("hasMore", false)) break
            page++
        }

        return images.map { url ->
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }
    
    private fun apiUrl(endpoint: String, page: HttpUrl, resource: String? = null): HttpUrl =
        "$baseUrl/api/$endpoint".toHttpUrl().newBuilder().apply {
            resource?.let { addQueryParameter("resource", it) }
            addQueryParameter("path", page.encodedPath + (page.encodedQuery?.let { "?$it" } ?: ""))
        }.build()

    private fun parseIsoDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
    }

    private data class ChapterPage(val items: List<JSONObject>, val total: Int)

    companion object {
        private const val CHAPTERS_PER_PAGE = 30
    }
}
