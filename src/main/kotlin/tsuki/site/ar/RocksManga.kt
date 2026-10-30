package tsuki.site.ar

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
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.NextJsData
import tsuki.util.generateUid
import tsuki.util.parseHtml

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.time.Instant
import java.util.EnumSet

@MangaSourceParser("ROCKSMANGA", "Rocks Manga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ROCKSMANGA, 40) {

    override val configKeyDomain = ConfigKey.Domain("rocksmanga.com")

    private val apiBaseUrl get() = "https://api.$domain"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    private var genresCache: Set<MangaTag>? = null
    private val genresMutex = Mutex()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = genresMutex.withLock {
            genresCache ?: fetchTags().also { genresCache = it }
        }
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
            ),
        )
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/browse".toHttpUrl()).parseHtml()
        val html = doc.outerHtml()

        val browse = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("genres") && node.has("cards")
        } as? JSONObject ?: return emptySet()

        val arr = browse.optJSONArray("genres") ?: return emptySet()
        return (0 until arr.length()).mapNotNull { i ->
            val g = arr.optJSONObject(i) ?: return@mapNotNull null
            val slug = g.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = g.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaTag(key = slug, title = name, source = source)
        }.toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.POPULARITY -> "most_viewed"
            SortOrder.ALPHABETICAL -> "alphabetical"
            SortOrder.NEWEST -> "new"
            else -> "recently_updated"
        }

        val url = "https://$domain/browse".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            addQueryParameter("sort", sort)
            if (sort == "alphabetical") addQueryParameter("direction", "asc")

            filter.query?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("search", it.trim())
            }

            filter.types.firstOrNull()?.let {
                val t = when (it) {
                    ContentType.MANGA   -> "manga"
                    ContentType.MANHWA  -> "manhwa"
                    ContentType.MANHUA  -> "manhua"
                    ContentType.COMICS  -> "comic"
                    else -> null
                }
                if (t != null) addQueryParameter("type", t)
            }

            filter.states.firstOrNull()?.let {
                val s = when (it) {
                    MangaState.ONGOING   -> "releasing"
                    MangaState.FINISHED  -> "completed"
                    MangaState.ABANDONED -> "canceled"
                    MangaState.PAUSED    -> "on_hold"
                    else -> null
                }
                if (s != null) addQueryParameter("status", s)
            }

            if (filter.tags.isNotEmpty()) {
                addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
            }
        }.build()

        val doc = webClient.httpGet(url).parseHtml()
        val html = doc.outerHtml()

        val browse = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("cards") && node.has("genres")
        } as? JSONObject

        if (browse != null) {
            val cards = browse.optJSONArray("cards")
            if (cards != null) {
                val out = (0 until cards.length()).mapNotNull { i ->
                    cards.optJSONObject(i)?.let(::parseCardJson)
                }
                if (out.isNotEmpty()) return out
            }
        }
        return doc.select("div.catalog-grid-compact div.unit, main div.unit")
            .distinct()
            .mapNotNull(::parseCardDom)
    }

    private fun parseCardJson(card: JSONObject): Manga? {
        val slug = card.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val title = card.optString("title").takeIf { it.isNotBlank() } ?: return null
        val coverUrl = card.optString("coverUrl").takeIf { it.isNotBlank() }?.let(::absApiUrl)

        val state = when (card.optString("status")) {
            "RELEASING" -> MangaState.ONGOING
            "COMPLETED" -> MangaState.FINISHED
            "ON_HOLD"   -> MangaState.PAUSED
            "CANCELED"  -> MangaState.ABANDONED
            else -> null
        }

        return Manga(
            id = generateUid(slug),
            url = "/manga/$slug",
            publicUrl = "https://$domain/manga/$slug",
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = if (card.optBoolean("isNsfw", false))
                ContentRating.ADULT else ContentRating.SAFE,
        )
    }

    private fun parseCardDom(unit: org.jsoup.nodes.Element): Manga? {
        val posterLink = unit.selectFirst("a.poster[href]") ?: return null
        val href = posterLink.attr("href").takeIf { it.startsWith("/manga/") } ?: return null
        val title = unit.selectFirst("div.info > a[href]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val coverUrl = unit.selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }?.let(::absApiUrl)

        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = "https://$domain$href",
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            source = source,
            contentRating = null,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = "https://$domain${manga.url}".toHttpUrl()
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val html = doc.outerHtml()

        val detailsObj = NextJsData.findValue(html) { node ->
            node is JSONObject &&
                    node.has("manga") &&
                    node.optJSONObject("manga")?.has("slug") == true
        } as? JSONObject

        val chaptersObj = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("mangaSlug") && node.has("chapters")
        } as? JSONObject

        var title = manga.title
        var coverUrl = manga.coverUrl
        var description: String? = null
        var author: String? = null
        var state: MangaState? = null
        var tags: Set<MangaTag> = emptySet()
        val altTitles = mutableSetOf<String>()

        if (detailsObj != null) {
            val m = detailsObj.optJSONObject("manga")
            if (m != null) {
                m.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
                m.optString("cover_url").takeIf { it.isNotBlank() }
                    ?.let { coverUrl = absApiUrl(it) }
                m.optString("synopsis").takeIf { it.isNotBlank() }
                    ?.let { description = it }

                m.optString("status").takeIf { it.isNotBlank() }?.let {
                    state = when (it) {
                        "RELEASING" -> MangaState.ONGOING
                        "COMPLETED" -> MangaState.FINISHED
                        "ON_HOLD"   -> MangaState.PAUSED
                        "CANCELED"  -> MangaState.ABANDONED
                        else -> null
                    }
                }

                m.optJSONArray("authors")?.let { arr ->
                    val names = (0 until arr.length()).mapNotNull {
                        arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() }
                    }
                    if (names.isNotEmpty()) author = names.joinToString(", ")
                }

                m.optJSONArray("genres")?.let { arr ->
                    tags = (0 until arr.length()).mapNotNull {
                        val g = arr.optJSONObject(it) ?: return@mapNotNull null
                        val name = g.optString("name").takeIf { n -> n.isNotBlank() }
                            ?: return@mapNotNull null
                        val slug = g.optString("slug").takeIf { s -> s.isNotBlank() }
                            ?: return@mapNotNull null
                        MangaTag(key = slug, title = name, source = source)
                    }.toSet()
                }

                m.optString("alternative_titles").takeIf { it.isNotBlank() }?.let { alt ->
                    if (alt.startsWith("[")) {
                        runCatching {
                            val arr = JSONArray(alt)
                            (0 until arr.length()).forEach { i ->
                                arr.optJSONObject(i)?.optString("title")
                                    ?.takeIf { t -> t.isNotBlank() }
                                    ?.let(altTitles::add)
                            }
                        }
                    } else {
                        alt.split(",", "،", ";").map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach(altTitles::add)
                    }
                }
            }
        }

        doc.selectFirst("div.info h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?.let { title = it }
        doc.selectFirst("div.poster img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?.let { coverUrl = absApiUrl(it) }
        if (description.isNullOrBlank()) {
            description = doc.selectFirst("div.description")?.html()?.takeIf { it.isNotBlank() }
        }
        if (tags.isEmpty()) {
            tags = doc.select("div.meta a[href*=genre]").mapNotNull { a ->
                val slug = a.attr("href").substringAfter("genre=").substringBefore("&")
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = a.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MangaTag(key = slug, title = name, source = source)
            }.toSet()
        }
        if (author.isNullOrBlank()) {
            author = doc.selectFirst("div.meta span[itemprop=author]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        val ratingText = doc.selectFirst("div.rating-box span[itemprop=ratingValue]")?.text()
        val rating = ratingText?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN

        val chapters = if (chaptersObj != null) {
            val slug = chaptersObj.optString("mangaSlug").takeIf { it.isNotBlank() }
            if (slug != null) {
                parseChaptersJson(chaptersObj.optJSONArray("chapters"), slug)
            } else {
                parseChaptersDom(doc)
            }
        } else {
            parseChaptersDom(doc)
        }

        return manga.copy(
            title = title,
            altTitles = manga.altTitles + altTitles,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            description = description,
            tags = tags,
            state = state,
            authors = setOfNotNull(author),
            rating = rating,
            chapters = chapters,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
            publicUrl = "https://$domain${manga.url}",
        )
    }

    private fun parseChaptersDom(doc: Document): List<MangaChapter> {
        return doc.select("ul.scroll-sm li.item").mapNotNull { li ->
            val a = li.selectFirst("a.chapter-row-link") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.startsWith("/manga/") } ?: return@mapNotNull null

            val displayName = a.selectFirst("span.chapter-title-text")?.text()?.trim()

            val numberStr = li.attr("data-number").takeIf { it.isNotBlank() }
                ?: displayName?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value }
                ?: return@mapNotNull null

            MangaChapter(
                id = generateUid(href),
                url = href,
                title = displayName ?: "الفصل $numberStr",
                number = numberStr.toFloatOrNull() ?: -1f,
                volume = 0,
                branch = null,
                uploadDate = 0L,
                scanlator = a.selectFirst("span.chapter-row-uploader")
                    ?.text()?.trim()?.takeIf { it.isNotBlank() },
                source = source,
            )
        }.sortedBy { it.number }
    }

    private fun parseChaptersJson(arr: JSONArray?, mangaSlug: String): List<MangaChapter> {
        if (arr == null || arr.length() == 0) return emptyList()
        val result = ArrayList<MangaChapter>(arr.length())

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val number = c.optString("number").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val href = "/manga/$mangaSlug/chapter/$number"

            val rawTitle = c.optString("title").takeIf { it.isNotBlank() && it != "null" }
            val displayTitle = if (rawTitle == null) "Chapter $number" else "Chapter $number - $rawTitle"

            val scanlator = c.optJSONObject("uploader")
                ?.optString("display_name")
                ?.takeIf { it.isNotBlank() && it != "null" }

            result += MangaChapter(
                id = generateUid(href),
                url = href,
                title = displayTitle,
                number = number.toFloatOrNull() ?: -1f,
                volume = 0,
                branch = null,
                uploadDate = parseIsoDate(c.optString("created_at").takeIf { it.isNotBlank() && it != "null" }),
                scanlator = scanlator,
                source = source,
            )
        }
        return result.sortedBy { it.number }
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "https://$domain${chapter.url}".toHttpUrl()
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val html = doc.outerHtml()

        val readerObj = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("chapter") && node.has("manga")
        } as? JSONObject

        val flightPaths: List<String> = readerObj
            ?.optJSONObject("chapter")
            ?.optJSONArray("pages")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotBlank() }
                }
            }
            .orEmpty()

        if (flightPaths.isNotEmpty()) {
            return flightPaths.map { path ->
                val abs = absApiUrl(path)
                MangaPage(id = generateUid(abs), url = abs, preview = null, source = source)
            }
        }

        val looseArr = NextJsData.findValue(html) { node ->
            node is JSONArray && node.length() > 0 &&
                    (node.optString(0).contains("/pages/") ||
                            node.optString(0).contains("/chapters/"))
        } as? JSONArray

        if (looseArr != null) {
            val paths = (0 until looseArr.length()).mapNotNull { i ->
                looseArr.optString(i).takeIf { it.isNotBlank() }
            }
            if (paths.isNotEmpty()) {
                return paths.map { path ->
                    val abs = absApiUrl(path)
                    MangaPage(id = generateUid(abs), url = abs, preview = null, source = source)
                }
            }
        }

        return doc.select("#ch-images .page-wrap img.page-img")
            .ifEmpty { doc.select("#ch-images img.page-img") }
            .ifEmpty { doc.select("#pages-container img.page-img") }
            .mapNotNull { img ->
                val src = img.attr("src").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                MangaPage(id = generateUid(src), url = src, preview = null, source = source)
            }
    }

    private fun absApiUrl(path: String): String = when {
        path.startsWith("http://", ignoreCase = true) ||
                path.startsWith("https://", ignoreCase = true) -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> "$apiBaseUrl$path"
        else -> "$apiBaseUrl/$path"
    }

    private fun parseIsoDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
    }
}
