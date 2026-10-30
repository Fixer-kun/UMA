package tsuki.site.all.nsfw

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

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseRaw

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.jsoup.parser.Parser
import java.time.Instant
import java.util.EnumSet

private const val API_LIMIT = 20
private val SCALED_SUFFIX = Regex("""-scaled(\.\w+)$""")
private const val TAG_SEARCH_KEY = "__tag_search__"

internal abstract class FSIComicsParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    lang: String,
    domain: String,
) : PagedMangaParser(context, source, API_LIMIT) {

    override val configKeyDomain = ConfigKey.Domain(domain)

    private val baseUrl get() = "https://$domain"

    private val excludedCategories: Set<Int> = when (lang) {
        "en" -> setOf(320)
        else -> emptySet()
    }

    private val filterParentId: Int = when (lang) {
        "en" -> 318
        else -> 0
    }

    private val descriptionEndMarkers = when (lang) {
        "es" -> listOf("es el editor de este episodio")
        "fr" -> listOf("est l’auteur de cet épisode", "publie cet épisode")
        "de" -> listOf("ist der Herausgeber dieser", "ist die Herausgeberin dieser")
        "it" -> listOf("è l’editore di questo episodio")
        else -> listOf("Publisher of This Comic Book Episode", "Watch comics feature genres")
    }

    private val tagIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val categories = fetchCategories()
        val children = categories.groupBy { it.parent }
        fun subtree(id: Int): List<Int> = listOf(id) + children[id].orEmpty().flatMap { subtree(it.id) }
        val topLevel = categories
            .filter { it.parent == filterParentId && it.slug != "uncategorized" && it.slug != "uncategorised" }
            .map { cat ->
                MangaTag(
                    key = subtree(cat.id).joinToString(","),
                    title = cat.name,
                    source = source,
                )
            }
            .toMutableSet()

        topLevel += MangaTag(key = TAG_SEARCH_KEY, title = "Search by tag…", source = source)

        return MangaListFilterOptions(availableTags = topLevel)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (filter.query.orEmpty().startsWith("http://") || filter.query.orEmpty().startsWith("https://")) {
            val url = runCatching { filter.query.orEmpty().toHttpUrl() }.getOrNull() ?: return emptyList()
            if (url.host != baseUrl.toHttpUrl().host) return emptyList()
            val path = url.encodedPath
            return listOf(
                Manga(
                    id = generateUid(path),
                    url = path,
                    publicUrl = baseUrl + path,
                    title = path.substringAfterLast('/').replace('-', ' ').trim()
                        .replaceFirstChar { it.uppercase() },
                    altTitles = emptySet(),
                    coverUrl = null,
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                ),
            )
        }

        val selectedTags = filter.tags
        val tagSearchMode = selectedTags.any { it.key == TAG_SEARCH_KEY }

        val categoryCsv = selectedTags.firstOrNull { it.key != TAG_SEARCH_KEY }?.key

        val tagId = if (tagSearchMode && filter.query.orEmpty().isNotBlank()) {
            resolveTagId(filter.query.orEmpty())
        } else {
            null
        }

        if (tagSearchMode && filter.query.orEmpty().isNotBlank() && tagId == null) {
            return emptyList()
        }

        val url = when (order) {
            SortOrder.POPULARITY -> popularUrl(page)
            else -> latestUrl(page, order, filter.query.orEmpty(), categoryCsv, tagId)
        }

        val items = JSONArray(webClient.httpGet(url).parseRaw().trim())

        return (0 until items.length())
            .mapNotNull { i -> items.optJSONObject(i)?.let { parseCard(it) } }
    }

    private suspend fun resolveTagId(query: String): String? {
        val norm = query.trim().lowercase()
        tagIdCache[norm]?.let { return it }

        val raw = webClient.httpGet(
            "$baseUrl/wp-json/wp/v2/tags".toHttpUrl().newBuilder()
                .addQueryParameter("search", query.trim())
                .addQueryParameter("per_page", "100")
                .addQueryParameter("_fields", "id,name,slug")
                .build(),
        ).parseRaw().trim()

        val tags = JSONArray(raw).let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Triple(it.optInt("id"), it.optString("name"), it.optString("slug"))
                }
            }
        }

        val match = tags.firstOrNull { (_, name, slug) ->
            name.equals(norm, true) || slug.equals(norm, true)
        } ?: return null

        val id = match.first.toString()
        tagIdCache[norm] = id
        return id
    }

    private fun popularUrl(page: Int): HttpUrl =
        "$baseUrl/wp-json/wordpress-popular-posts/v1/popular-posts".toHttpUrl().newBuilder()
            .addQueryParameter("limit", API_LIMIT.toString())
            .addQueryParameter("offset", ((page - 1) * API_LIMIT).toString())
            .addQueryParameter("range", "last30days")
            .addQueryParameter("order_by", "views")
            .addQueryParameter("embed", "true")
            .addQueryParameter("_embed", "wp:featuredmedia")
            .addQueryParameter("_fields", "title,link,categories,_embedded,_links.wp:featuredmedia")
            .build()

    private fun latestUrl(
        page: Int,
        order: SortOrder,
        query: String,
        categoryCsv: String?,
        tagId: String?,
    ): HttpUrl =
        "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", API_LIMIT.toString())
            .addQueryParameter("_embed", "wp:featuredmedia")
            .addQueryParameter("_fields", "link,title,categories,_embedded,_links.wp:featuredmedia")
            .apply {
                if (query.isNotEmpty() && tagId == null) {
                    addQueryParameter("search", query)
                }
                categoryCsv?.takeIf { it.isNotBlank() }?.let { addQueryParameter("categories", it) }
                tagId?.let { addQueryParameter("tags", it) }
                when (order) {
                    SortOrder.UPDATED, SortOrder.NEWEST -> {
                        addQueryParameter("orderby", "date")
                        addQueryParameter("order", "desc")
                    }
                    SortOrder.ALPHABETICAL -> {
                        addQueryParameter("orderby", "title")
                        addQueryParameter("order", "asc")
                    }
                    else -> addQueryParameter("orderby", "date")
                }
            }
            .build()

    private fun parseCard(obj: org.json.JSONObject): Manga? {
        val link = obj.optString("link").takeIf { it.isNotBlank() } ?: return null
        val titleObj = obj.optJSONObject("title") ?: return null
        val rendered = titleObj.optString("rendered").takeIf { it.isNotBlank() } ?: return null
        val title = Parser.unescapeEntities(rendered, false)

        val path = link.toHttpUrl().encodedPath

        val embedded = obj.optJSONObject("_embedded")
        val thumbnail = embedded
            ?.optJSONArray("wp:featuredmedia")
            ?.optJSONObject(0)
            ?.optString("source_url")

        val categories = obj.optJSONArray("categories")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optInt(it) }
        } ?: emptyList()

        if (categories.any { it in excludedCategories }) return null

        return Manga(
            id = generateUid(path),
            url = path,
            publicUrl = baseUrl + path,
            title = title,
            altTitles = emptySet(),
            coverUrl = thumbnail,
            rating = RATING_UNKNOWN,
            contentRating = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = (baseUrl + manga.url).toHttpUrl()
        val doc = webClient.httpGet(url).parseHtml()

        val title = doc.selectFirst("h1.s-title")?.text()?.trim() ?: manga.title

        val description = doc.select("div.entry-content > p.wp-block-paragraph")
            .takeWhile { p -> descriptionEndMarkers.none { p.text().contains(it, true) } }
            .joinToString("\n\n") { it.text() }
            .ifBlank { null }

        val genres = doc.select("a[rel=tag]").eachText()
        val tags = genres.map { MangaTag(key = it, title = it, source = source) }.toSet()

        val thumbnail = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val chapter = MangaChapter(
            id = generateUid(manga.url),
            url = manga.url,
            title = "Chapter",
            number = 0f,
            volume = 0,
            uploadDate = doc.selectFirst("time.published")
                ?.attr("datetime")
                ?.let { parseIsoDate(it) } ?: 0L,
            scanlator = null,
            branch = null,
            source = source,
        )

        return manga.copy(
            title = title,
            description = description,
            tags = tags,
            coverUrl = thumbnail ?: manga.coverUrl,
            largeCoverUrl = thumbnail ?: manga.largeCoverUrl,
            state = MangaState.FINISHED,
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = (baseUrl + chapter.url).toHttpUrl()
        val doc = webClient.httpGet(url).parseHtml()

        return doc.select(".entry-content .wp-block-gallery img")
            .mapNotNull { it.attr("abs:src").takeIf { src -> src.isNotBlank() && !src.startsWith("data:image") } }
            .map { SCALED_SUFFIX.replace(it, "$1") }
            .distinct()
            .mapIndexed { _, imgUrl ->
                MangaPage(id = generateUid(imgUrl), url = imgUrl, preview = null, source = source)
            }
    }

    private suspend fun fetchCategories(): List<WPCategory> {
        val all = mutableListOf<WPCategory>()
        var page = 1
        while (true) {
            val raw = webClient.httpGet(
                "$baseUrl/wp-json/wp/v2/categories?per_page=100&page=$page&_fields=id,parent,name,slug".toHttpUrl(),
            ).parseRaw().trim()
            val batch = parseCategoryArray(JSONArray(raw))
            if (batch.isEmpty()) break
            all += batch
            if (batch.size < 100) break
            page++
        }
        return all
    }

    private fun parseCategoryArray(arr: JSONArray): List<WPCategory> =
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                WPCategory(
                    id = it.optInt("id"),
                    parent = it.optInt("parent"),
                    name = it.optString("name"),
                    slug = it.optString("slug"),
                )
            }
        }

    private fun parseIsoDate(text: String): Long =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)

    private data class WPCategory(
        val id: Int,
        val parent: Int,
        val name: String,
        val slug: String,
    )
}

@MangaSourceParser("FSICOMICS_EN", "FSI Comics (EN)", "en", ContentType.HENTAI)
internal class FSIComicsEN(context: MangaLoaderContext) :
    FSIComicsParser(context, MangaParserSource.FSICOMICS_EN, "en", "fsicomics.com")

@MangaSourceParser("FSICOMICS_ES", "FSI Comics (ES)", "es", ContentType.HENTAI)
internal class FSIComicsES(context: MangaLoaderContext) :
    FSIComicsParser(context, MangaParserSource.FSICOMICS_ES, "es", "es.fsicomics.com")

@MangaSourceParser("FSICOMICS_DE", "FSI Comics (DE)", "de", ContentType.HENTAI)
internal class FSIComicsDE(context: MangaLoaderContext) :
    FSIComicsParser(context, MangaParserSource.FSICOMICS_DE, "de", "de.fsicomics.com")

@MangaSourceParser("FSICOMICS_FR", "FSI Comics (FR)", "fr", ContentType.HENTAI)
internal class FSIComicsFR(context: MangaLoaderContext) :
    FSIComicsParser(context, MangaParserSource.FSICOMICS_FR, "fr", "fr.fsicomics.com")

@MangaSourceParser("FSICOMICS_IT", "FSI Comics (IT)", "it", ContentType.HENTAI)
internal class FSIComicsIT(context: MangaLoaderContext) :
    FSIComicsParser(context, MangaParserSource.FSICOMICS_IT, "it", "it.fsicomics.com")
