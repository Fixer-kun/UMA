package tsuki.site.en.nsfw

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

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.urlEncoded

import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.EnumSet

@MangaSourceParser("MILFTOON", "MilfToon", "en", ContentType.HENTAI)
internal class MilfToon(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MILFTOON, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("milftoon.xxx")
    private val baseUrl = "https://$domain"
    private val cdnUrl = "https://fdc1.azhentai.com"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    private var genresCache: Set<MangaTag>? = null

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = genresCache ?: fetchGenres().also { genresCache = it }
        return MangaListFilterOptions(
            availableTags = genres,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
            ),
            availableContentTypes = emptySet(),
        )
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        val doc = webClient.httpGet("$baseUrl/manga_list/all").parseHtml()
        val data = extractRouteData(doc) ?: return emptySet()
        val facets = data.optJSONObject("facets") ?: return emptySet()
        val genresArray = facets.optJSONArray("genres") ?: return emptySet()

        return (0 until genresArray.length()).mapNotNull { i ->
            val obj = genresArray.getJSONObject(i)
            val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaTag(name, slug, source)
        }.toSet()
    }
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()
        val url = buildString {
            if (!query.isNullOrBlank()) {
                append("$baseUrl/search_result?keyword=")
                append(query.urlEncoded())
                if (page > 1) {
                    append("&page=")
                    append(page)
                }
            } else {
                append("$baseUrl/manga_list/all")
                val params = mutableListOf<String>()

                when (order) {
                    SortOrder.UPDATED -> params.add("orderby=latest")
                    SortOrder.POPULARITY -> params.add("orderby=views")
                    SortOrder.NEWEST -> params.add("orderby=new")
                    SortOrder.ALPHABETICAL -> {}
                    else -> params.add("orderby=new")
                }

                filter.tags.forEach { tag ->
                    params.add("genre=${tag.key.urlEncoded()}")
                }

                if (page > 1) {
                    params.add("page=$page")
                }

                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        val data = extractRouteData(doc) ?: return emptyList()
        val items = data.optJSONArray("items") ?: return emptyList()

        return (0 until items.length()).mapNotNull { i ->
            parseMangaFromJson(items.getJSONObject(i))
        }
    }

    private fun extractRouteData(doc: Document): JSONObject? {
        val marker = "window.__INITIAL_ROUTE_DATA__="

        val script = doc.select("script").firstOrNull { it.data().contains(marker) }
            ?: return null

        val text = script.data()
        val markerStart = text.indexOf(marker)
        if (markerStart == -1) return null

        val jsonStart = markerStart + marker.length
        if (jsonStart >= text.length || text[jsonStart] != '{') return null

        var depth = 0
        var inString = false
        var escaped = false
        var jsonEnd = -1

        for (i in jsonStart until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) { jsonEnd = i; break }
                }
            }
        }

        if (jsonEnd == -1) return null

        return try {
            JSONObject(text.substring(jsonStart, jsonEnd + 1)).optJSONObject("data")
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMangaFromJson(obj: JSONObject): Manga? {
        val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val coverPath = obj.optString("coverPath").takeIf { it.isNotBlank() }

        val coverUrl = coverPath?.let { "$cdnUrl/$it" }

        val state = when (obj.optString("status")) {
            "end" -> MangaState.FINISHED
            "ongoing" -> MangaState.ONGOING
            else -> null
        }

        val rating = obj.optDouble("rating", -1.0)
            .takeIf { it >= 0.0 }
            ?.let { (it / 10.0).toFloat() }
            ?: RATING_UNKNOWN

        val author = obj.optString("author").takeIf { it.isNotBlank() }

        return Manga(
            id = generateUid(slug),
            url = slug,
            publicUrl = "$baseUrl/comics/$slug",
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            rating = rating,
            contentRating = ContentRating.ADULT,
            tags = emptySet(),
            state = state,
            authors = setOfNotNull(author),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val readerUrl = "$baseUrl/comics/${manga.url}"
        val doc = webClient.httpGet(readerUrl).parseHtml()

        val title = doc.selectFirst("h1.reader__post-title")?.text()?.trim()
            ?: manga.title

        val chapter = MangaChapter(
            id = generateUid("${manga.url}-ch-1"),
            title = "Chapter 1",
            number = 1f,
            volume = 0,
            url = readerUrl,
            uploadDate = 0L,
            source = source,
            scanlator = null,
            branch = null,
        )

        return manga.copy(
            title = title,
            chapters = listOf(chapter),
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        return doc.select("li.reader-page img.reader-page__image")
            .mapNotNull { img ->
                val src = img.absUrl("src").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                MangaPage(
                    id = generateUid(src),
                    url = src,
                    preview = null,
                    source = source,
                )
            }
            .distinctBy { it.url }
    }
}
