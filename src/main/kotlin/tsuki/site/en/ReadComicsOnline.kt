package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MmrcmsParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.model.MangaListFilterOptions

import tsuki.util.generateUid
import tsuki.util.parseJson
import tsuki.util.urlEncoded
import tsuki.util.attrAsRelativeUrl
import tsuki.util.mapChapters
import tsuki.util.nullIfEmpty
import tsuki.util.parseHtml
import tsuki.util.parseSafe
import tsuki.util.requireSrc
import tsuki.util.src
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.util.mapNotNullToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

// TODO: getListpage use advanced-search

@MangaSourceParser("READCOMICSONLINE", "ReadComicsOnline.ru", "en", ContentType.COMICS)
internal class ReadComicsOnline(context: MangaLoaderContext) :
    MmrcmsParser(context, MangaParserSource.READCOMICSONLINE, "readcomicsonline.ru") {

    private val chapterDateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override val fetchFilterOptions = false

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            return search(page, filter.query!!)
        }
        if (filter.tags.isNotEmpty() || filter.states.isNotEmpty()) {
            return emptyList()
        }

        val sort = when (order) {
            SortOrder.UPDATED -> "latest"
            else -> "views"
        }
        val doc = webClient.httpGet("https://$domain/comic-list?sort=$sort&page=$page").parseHtml()
        return doc.select("div.comic-list-layout .grid > .group").mapNotNull(::parseMangaListItem)
    }

    private suspend fun search(page: Int, query: String): List<Manga> {
        if (page > 1) return emptyList()

        val url = "https://$domain/search?query=${query.trim().urlEncoded()}"
        val suggestions = webClient.httpGet(url).parseJson()
            .optJSONArray("suggestions") ?: return emptyList()

        return (0 until suggestions.length()).mapNotNull { i ->
            val jo = suggestions.optJSONObject(i) ?: return@mapNotNull null
            val slug = jo.optString("data").nullIfEmpty() ?: return@mapNotNull null
            val href = "/comic/$slug"
            Manga(
                id = generateUid(href),
                title = jo.optString("value").nullIfEmpty() ?: slug,
                altTitles = emptySet(),
                url = href,
                publicUrl = jo.optString("url").nullIfEmpty()
                    ?: href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = guessCover(href, jo.optString("cover").nullIfEmpty()),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private fun parseMangaListItem(element: Element): Manga? {
        val anchor = element.selectFirst("a.block.text-sm.font-semibold") ?: return null
        val href = anchor.attrAsRelativeUrl("href")
        return Manga(
            id = generateUid(href),
            title = anchor.text(),
            altTitles = emptySet(),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            rating = RATING_UNKNOWN,
            contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            coverUrl = guessCover(href, element.selectFirst("img")?.src()),
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val chaptersDeferred = async { getChapters(doc, manga.title) }

        val title = doc.selectFirst("h1.text-2xl")?.textOrNull() ?: manga.title

        val coverFromPage = doc.selectFirst("img.w-full.rounded-xl")
            ?.attr("src")
            ?.nullIfEmpty()
            ?.takeUnless { it.contains("cover_missing", ignoreCase = true) }
        val coverUrl = coverFromPage
            ?: guessCover(manga.url, null)
            ?: manga.coverUrl

        val description = doc.selectFirst("div.bg-ink-900 > p.text-sm")?.textOrNull()

        val statusText = doc.select("div.mt-4.flex.flex-wrap.gap-2 > span")
            .mapNotNull { it.textOrNull() }
            .firstOrNull { it.lowercase(Locale.US) in STATUS_KEYWORDS }

        val publisher = doc.selectFirst("span.rc-chip")?.textOrNull()

        val tags = doc.select("dl div:contains(Genres:) a").mapNotNullToSet { a ->
            val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
            if (key.isBlank()) return@mapNotNullToSet null
            MangaTag(key = key, title = a.text(), source = source)
        }

        manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = description,
            state = parseState(statusText),
            tags = tags,
            authors = setOfNotNull(publisher),
            chapters = chaptersDeferred.await(),
        )
    }

    private fun parseState(value: String?): MangaState? =
        when (value?.lowercase(Locale.US)) {
            "complete", "completed" -> MangaState.FINISHED
            "ongoing", "on going" -> MangaState.ONGOING
            "dropped", "cancelled", "canceled" -> MangaState.ABANDONED
            else -> null
        }

    private fun getChapters(doc: Document, mangaTitle: String): List<MangaChapter> {
        return doc.select(".overflow-hidden.border-ink-600 > a")
            .mapChapters(reversed = true) { i, element ->
                val href = element.attrAsRelativeUrl("href")
                val chapterName = element.selectFirst(".text-brand-400")?.textOrNull()
                    ?: element.text()
                MangaChapter(
                    id = generateUid(href),
                    title = cleanChapterName(mangaTitle, chapterName),
                    number = i + 1f,
                    volume = 0,
                    url = href,
                    uploadDate = chapterDateFormat.parseSafe(
                        element.selectFirst(".text-slate-500")?.text(),
                    ),
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#reader-all img").mapNotNull { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    private fun cleanChapterName(mangaTitle: String, chapterName: String): String {
        return chapterName
            .removePrefix(mangaTitle)
            .trimStart(' ', '-', ':')
            .nullIfEmpty()
            ?: chapterName
    }

    override fun guessCover(mangaUrl: String, url: String?): String? {
        url?.takeUnless { it.contains("/cover/cover_missing.", ignoreCase = true) }?.let {
            return it
        }
        val slug = mangaUrl.removeSuffix("/").substringAfterLast('/').nullIfEmpty() ?: return null
        return "https://$domain/uploads/manga/$slug/cover/cover_250x350.jpg"
    }

    companion object {
        private val STATUS_KEYWORDS = setOf(
            "ongoing", "on going", "completed", "complete",
            "dropped", "cancelled", "canceled", "hiatus",
        )
    }
}
