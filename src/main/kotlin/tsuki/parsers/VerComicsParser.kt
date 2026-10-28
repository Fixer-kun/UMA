package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
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

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal abstract class VerComicsParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 24,
) : PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)

    /** Path segment for the “popular / all” listing (e.g. `sex-comics`). */
    protected open val urlSuffix: String = ""

    /** Path segment that precedes a genre slug (e.g. `tags`). */
    protected open val genreSuffix: String = ""

    protected abstract val genres: Array<Pair<String, String>>

    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.POPULARITY)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
            availableTags = genres
                .filter { it.second.isNotBlank() }
                .mapTo(mutableSetOf()) { (name, slug) ->
                    MangaTag(key = slug, title = name, source = source)
                },
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = when {
            !filter.query.isNullOrBlank() -> {
                // /<urlSuffix>/page/N?s=<query>
                buildString {
                    append("https://$domain")
                    if (urlSuffix.isNotBlank()) append("/$urlSuffix")
                    append("/page/").append(page)
                    append("?s=").append(filter.query!!.urlEncoded())
                }
            }
            filter.tags.isNotEmpty() -> {
                // /<genreSuffix>/<slug>/page/N
                val slug = filter.tags.first().key
                "https://$domain/$genreSuffix/$slug/page/$page"
            }
            else -> {
                // /<urlSuffix>/page/N  (popular)
                "https://$domain/$urlSuffix/page/$page"
            }
        }
        val doc = webClient.httpGet(url.toHttpUrl()).parseHtml()
        return parseMangaList(doc)
    }

    protected open fun parseMangaList(doc: Document): List<Manga> =
        doc.select(popularMangaSelector()).mapNotNull { popularMangaFromElement(it) }

    protected open fun popularMangaSelector() = "section.pp-section:not(:has(.pp-random-more)) article.pp-card:not(.pp-card-pinned)"

    protected open fun popularMangaFromElement(element: Element): Manga? {
        val link = element.selectFirst("a.pp-card-link") ?: return null
        val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val title = link.selectFirst("h3.pp-card-title")?.text()
            ?.takeIf { it.isNotBlank() } ?: return null
        val thumbnail = link.selectFirst("img")?.let { imageAttr(it) }
        val path = href.toHttpUrl().encodedPath

        return Manga(
            id = generateUid(path),
            url = path,
            publicUrl = href,
            title = title,
            altTitles = emptySet(),
            coverUrl = thumbnail,
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    protected open val detailsSelector: String = "div.tax_post"
    protected open val genreSelector: String = "div.tax_box:has(div.title:contains(Etiquetas)) a[rel=tag]"

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://$domain${manga.url}".toHttpUrl()
        val doc = webClient.httpGet(url).parseHtml()

        val hasDetails = doc.selectFirst(detailsSelector) != null

        val tags: Set<MangaTag> = if (hasDetails) {
            doc.select(genreSelector).mapNotNullTo(mutableSetOf()) { a ->
                val title = a.text().removePrefix("#")
                    .replaceFirstChar { it.uppercase() }
                    .takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
                val slug = a.attr("href")
                    .substringAfter("$genreSuffix/", "")
                    .removeSuffix("/")
                    .takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
                MangaTag(key = slug, title = title, source = source)
            }
        } else {
            emptySet()
        }

        val chapter = MangaChapter(
            id = generateUid(manga.url),
            url = manga.url,
            title = manga.title,
            number = 0f,
            volume = 0,
            branch = null,
            uploadDate = 0L,
            scanlator = null,
            source = source,
        )

        return manga.copy(
            title = manga.title,
            tags = tags,
            state = if (hasDetails) MangaState.FINISHED else manga.state,
            chapters = listOf(chapter),
            contentRating = ContentRating.ADULT,
        )
    }

    protected open val pageListSelector: String =
        "div.wp-content p > img:not(noscript img), " +
                "div.wp-content div#lector > img:not(noscript img), " +
                "div.wp-content > figure img:not(noscript img), " +
                "div.wp-content > img, div.wp-content > p img, " +
                "div.post-imgs > img"

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}".toHttpUrl()
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(pageListSelector).mapIndexedNotNull { i, img ->
            val imageUrl = imageAttr(img) ?: return@mapIndexedNotNull null
            MangaPage(
                id = generateUid("$i|$imageUrl"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    protected open fun imageAttr(img: Element): String? = when {
        img.hasAttr("data-src")      -> img.attr("abs:data-src")
        img.hasAttr("data-lazy-src") -> img.attr("abs:data-lazy-src")
        img.hasAttr("srcset")        -> img.attr("abs:srcset").pickFromSrcSet()
        img.hasAttr("data-cfsrc")    -> img.attr("abs:data-cfsrc")
        else                         -> img.attr("abs:src")
    }.takeIf { it!!.isNotBlank() }

    private fun String.pickFromSrcSet(): String? = split(" ")
        .filter { URL_REGEX.matches(it) }
        .maxOfOrNull { it }

    private companion object {
        private val URL_REGEX = Regex("""^(https?://[^\s/$.?#].\S*)$""")
    }
}
