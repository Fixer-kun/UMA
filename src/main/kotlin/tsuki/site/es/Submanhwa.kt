package tsuki.site.es

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
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("SUBMANHWA", "Submanhwa", "es")
internal class Submanhwa(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SUBMANHWA, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("submanhwa.com")

    private val dateFormat = SimpleDateFormat("d MMM. yyyy", Locale.ENGLISH)

    @Volatile
    private var genresCache: Set<MangaTag>? = null

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Accept-Language", "es-PE,es;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = genresCache ?: fetchGenres().also { genresCache = it }
        return MangaListFilterOptions(
            availableTags = genres,
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        )
    }

    private suspend fun fetchGenres(): Set<MangaTag> = try {
        val doc = webClient.httpGet("https://$domain/biblioteca").parseHtml()
        doc.select("a.tag-chip").mapNotNull { chip ->
            val name = chip.ownText().trim()
            val id = chip.absUrl("href").toHttpUrl().queryParameter("genre")
            if (name.isNotBlank() && !id.isNullOrBlank()) {
                MangaTag(name, id, source)
            } else null
        }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val hasFilters = filter.tags.isNotEmpty() || filter.states.isNotEmpty()

        return if (query.isEmpty() && !hasFilters) {
            when (order) {
                SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> parsePopularList(page)
                SortOrder.UPDATED, SortOrder.UPDATED_ASC, SortOrder.RELEVANCE -> parseLatestList(page)
                else -> parseSearchList(page, query, order, filter)
            }
        } else {
            parseSearchList(page, query, order, filter)
        }
    }

    private suspend fun parsePopularList(page: Int): List<Manga> {
        val url = "https://$domain/filterList?page=$page&sortBy=views&asc=false"
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".series-card").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".series-title")?.text()
                ?: element.selectFirst("h3 a")?.text()
                ?: a.text()
            element.toManga(title, a.absUrl("href"))
        }
    }

    private suspend fun parseLatestList(page: Int): List<Manga> {
        if (page > 1) return emptyList()
        val doc = webClient.httpGet("https://$domain").parseHtml()
        return doc.select("article.up-card").mapNotNull { element ->
            val a = element.selectFirst("a.up-title") ?: element.selectFirst("a") ?: return@mapNotNull null
            element.toManga(a.text().trim(), a.absUrl("href"))
        }
    }

    private suspend fun parseSearchList(page: Int, query: String, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/biblioteca".toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) addQueryParameter("search", query)
            sortParam(order).takeIf { it.isNotEmpty() }?.let { addQueryParameter("sort", it) }
            when (filter.states.firstOrNull()) {
                MangaState.ONGOING -> addQueryParameter("status", "1")
                MangaState.FINISHED -> addQueryParameter("status", "2")
                else -> Unit
            }
            filter.tags.firstOrNull()?.key?.let { addQueryParameter("genre", it) }
            addQueryParameter("page", page.toString())
        }.build().toString()

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("a.lib-card").mapNotNull { element ->
            val title = element.selectFirst(".lib-title")?.text() ?: return@mapNotNull null
            element.toManga(title, element.absUrl("href"))
        }
    }

    private fun sortParam(order: SortOrder): String = when (order) {
        SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "new"
        SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "popular"
        SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "az"
        else -> ""
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val info = doc.selectFirst(".sr-info")
        val meta = info?.selectFirst(".sr-meta")

        val title = info?.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: manga.title
        val coverUrl = doc.selectFirst("img.sr-cover")?.absUrl("src") ?: manga.coverUrl

        val altNames = info?.selectFirst(".sr-alt")?.text()
            ?.split(Regex("""[|/•,;]"""))
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

        val description = buildString {
            info?.select(".sr-stats .sr-stat")?.joinToString(" | ") { it.text() }
                ?.takeIf { it.isNotBlank() }?.let { append(it) }
            doc.selectFirst(".sr-summary > p")?.text()?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
            if (altNames.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative names:\n")
                altNames.forEach { append("- ").append(it).append('\n') }
            }
        }.trim().ifBlank { null }

        val state = when {
            info?.selectFirst(".ongoing") != null -> MangaState.ONGOING
            info?.selectFirst(".ended") != null -> MangaState.FINISHED
            else -> null
        }

        val authors = meta?.select("dt:contains(Autor(es)) + dd a")
            ?.mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
            ?.toSet()
            .orEmpty()
        val artists = meta?.select("dt:contains(Artist(s)) + dd a")
            ?.mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
            ?.toSet()
            .orEmpty()

        val tags = info?.select(".sr-badge.type, .sr-cat, .sr-tag")
            ?.mapNotNull { el ->
                val text = el.text().trim()
                if (text.isBlank()) null else MangaTag(text, text, source)
            }
            ?.toSet()
            .orEmpty()

        val chapters = parseChapters(doc)

        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = description,
            authors = authors + artists,
            tags = tags,
            state = state,
            chapters = chapters,
            altTitles = altNames,
        )
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        return doc.select(".chapters-grid [class^=chapter-card]").mapNotNull { element ->
            val a = element.selectFirst("a.chapter-link") ?: return@mapNotNull null
            val name = a.text()
            val href = a.absUrl("href")
            if (href.isBlank()) return@mapNotNull null
            
            val dateText = element.selectFirst(".ch-date")
                ?.clone()
                ?.apply { select(".ch-new").remove() }
                ?.text()
                ?.replace("🕒", "")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()

            val uploadDate = dateText?.takeIf { it.isNotBlank() }?.let(::parseDate) ?: 0L
            val number = Regex("""\d+(\.\d+)?""").find(name)?.value?.toFloatOrNull() ?: 0f

            MangaChapter(
                id = generateUid(href),
                title = name,
                number = number,
                volume = 0,
                url = href,
                uploadDate = uploadDate,
                scanlator = null,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }
    }

    private fun parseDate(text: String): Long {
        val cleaned = text.trim()
        runCatching { dateFormat.parse(cleaned)?.time }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }

        val match = Regex("""(\d{1,2})\s+(\p{L}+)\.?\s+(\d{4})""").find(cleaned) ?: return 0L
        val normalized = "${match.groupValues[1]} ${match.groupValues[2]}. ${match.groupValues[3]}"
        return runCatching { dateFormat.parse(normalized)?.time ?: 0L }.getOrDefault(0L)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#all img").mapNotNull { img ->
            val url = img.imgAttr().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun Element.imgAttr(): String {
        if (hasAttr("data-src")) return absUrl("data-src")
        if (hasAttr("data-lazy-src")) return absUrl("data-lazy-src")
        return absUrl("src")
    }

    private fun Element.toManga(title: String, href: String): Manga? {
        if (title.isBlank() || href.isBlank()) return null
        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href,
            title = title,
            coverUrl = selectFirst("img")?.absUrl("src"),
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
