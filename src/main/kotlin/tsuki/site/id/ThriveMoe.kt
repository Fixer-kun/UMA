package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
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
import tsuki.util.parseSafe

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("THRIVEMOE", "Thrive.moe", "id")
internal class ThriveMoe(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVEMOE, 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")
    private val siteUrl get() = "https://$domain"

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$siteUrl/")
        .set("Origin", siteUrl)
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = false,
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = runCatching {
            val doc = webClient.httpGet(siteUrl, getRequestHeaders()).parseHtml()
            doc.select("a[href^=/genre/]")
                .mapNotNull { a ->
                    val href = a.attr("href").takeIf { it.startsWith("/genre/") }
                        ?: return@mapNotNull null
                    val slug = href.removePrefix("/genre/").trim('/')
                        .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = a.selectFirst("span.mx-1")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: slug.replaceFirstChar { it.titlecase(Locale.ROOT) }
                    MangaTag(name, slug, source)
                }
                .distinctBy { it.key }
                .toSet()
        }.getOrDefault(emptySet())

        return MangaListFilterOptions(
            availableTags = genres,
        )
    }


    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val genreTag = filter.tags.firstOrNull()
        if (genreTag != null) {
            val url = siteUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("genre")
                addPathSegment(genreTag.key)
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
            val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
            return doc.select("a[href^=/title/]")
                .mapNotNull(::parseMangaCard)
                .distinctBy { it.url }
        }
        
        if (!filter.query.isNullOrBlank()) {
            val url = siteUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("search")
                addQueryParameter("q", filter.query!!.trim())
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
            val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
            return doc.select("a[href^=/title/]")
                .mapNotNull(::parseMangaCard)
                .distinctBy { it.url }
        }

        if (page > 1) return emptyList()
        val doc = webClient.httpGet(siteUrl, getRequestHeaders()).parseHtml()
        return when (order) {
            SortOrder.POPULARITY -> parseCarousel(doc, "Populer")
            SortOrder.RELEVANCE -> parseCarousel(doc, "Project thrive")
            else -> parseCarousel(doc, "Update Terbaru")
        }
    }

    private fun parseCarousel(doc: Document, heading: String): List<Manga> {
        val h2 = doc.select("h2")
            .firstOrNull { it.text().contains(heading, ignoreCase = true) }
            ?: return emptyList()
        val container = h2.nextElementSibling() ?: return emptyList()
        return container.select("a[href^=/title/]")
            .mapNotNull(::parseMangaCard)
            .distinctBy { it.url }
    }

    private fun parseMangaCard(element: Element): Manga? {
        val href = element.attr("href").takeIf { it.startsWith("/title/") }
            ?: return null
        val mangaId = href.removePrefix("/title/").trim('/')
            .takeIf { it.isNotBlank() } ?: return null
        val cover = element.selectFirst("img")?.absUrl("src")
            ?.takeIf { it.isNotBlank() } ?: return null

        val title = (element.selectFirst("div.line-clamp-2")
            ?: element.selectFirst("span.text-xl"))
            ?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null

        return Manga(
            id = generateUid(mangaId),
            url = "/title/$mangaId",
            publicUrl = "$siteUrl/title/$mangaId",
            title = title,
            coverUrl = cover,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }
    
    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.substringAfterLast('/')
        if (mangaId.isBlank()) return manga
        return fetchDetails(mangaId, manga)
    }

    private suspend fun fetchDetails(mangaId: String, base: Manga): Manga {
        val doc = runCatching {
            webClient.httpGet("$siteUrl/title/$mangaId", getRequestHeaders())
                .parseHtml()
        }.getOrNull() ?: return base

        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return base
        val root = runCatching { JSONObject(nextData) }.getOrNull()
            ?: return base
        val props = root.optJSONObject("props")?.optJSONObject("pageProps")
            ?: return base

        return parseDetails(props, base)
    }

    private fun parseDetails(props: JSONObject, base: Manga): Manga {
        val title = props.optString("title").takeIf { it.isNotBlank() }
            ?: base.title

        val rawImage = props.optString("image").takeIf { it.isNotBlank() }
        val coverUrl = rawImage?.let { cdnCoverUrl(it, 256) } ?: base.coverUrl
        val largeCoverUrl = rawImage?.let { cdnCoverUrl(it, 512) } ?: base.largeCoverUrl

        val description = props.optString("desc_ID").takeIf { it.isNotBlank() }
            ?: props.optJSONObject("desc")?.optString("en")
                ?.takeIf { it.isNotBlank() }

        val altTitles = mutableSetOf<String>()
        props.optString("alt_title").takeIf { it.isNotBlank() }
            ?.let(altTitles::add)

        val authors = mutableSetOf<String>()
        props.optJSONArray("author")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }
                    ?.let(authors::add)
            }
        }
        props.optJSONArray("artist")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }
                    ?.let(authors::add)
            }
        }

        val tags = mutableSetOf<MangaTag>()
        props.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let {
                    tags.add(MangaTag(it, it, source))
                }
            }
        }
        props.optString("demographic").takeIf { it.isNotBlank() }?.let {
            val display = it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }
            tags.add(MangaTag(display, it, source))
        }

        val state = when (props.optString("status").lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled", "canceled" -> MangaState.ABANDONED
            else -> null
        }

        val contentRating = when (props.optString("rating").lowercase(Locale.ROOT)) {
            "safe" -> ContentRating.SAFE
            "suggestive", "erotica" -> ContentRating.SUGGESTIVE
            "pornographic" -> ContentRating.ADULT
            else -> null
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
        val chapters = mutableListOf<MangaChapter>()
        props.optJSONArray("chapterlist")?.let { arr ->
            for (i in 0 until arr.length()) {
                val ch = arr.optJSONObject(i) ?: continue
                val chapterId = ch.optString("chapter_id")
                    .takeIf { it.isNotBlank() } ?: continue
                val number = ch.optString("chapter_number").trim()
                if (number.isBlank()) continue

                val chapterTitle = if (ch.isNull("chapter_title")) {
                    null
                } else {
                    ch.optString("chapter_title").takeIf { it.isNotBlank() }
                }
                val scanlator = ch.optString("scanlator")
                    .takeIf { it.isNotBlank() }
                val uploadDate = dateFormat.parseSafe(
                    ch.optString("created_at").take(19),
                )
                val displayName = chapterTitle?.let { "Chapter $number - $it" }
                    ?: "Chapter $number"

                chapters.add(
                    MangaChapter(
                        id = generateUid(chapterId),
                        title = displayName,
                        number = number.toFloatOrNull() ?: 0f,
                        volume = 0,
                        url = "/read/$chapterId",
                        uploadDate = uploadDate,
                        scanlator = scanlator,
                        branch = null,
                        source = source,
                    ),
                )
            }
        }

        return base.copy(
            title = title,
            coverUrl = coverUrl,
            largeCoverUrl = largeCoverUrl,
            altTitles = altTitles,
            description = description,
            authors = authors,
            tags = tags,
            state = state,
            contentRating = contentRating,
            chapters = chapters.sortedBy { it.number },
        )
    }

    private fun cdnCoverUrl(raw: String, size: Int): String =
        raw.replace("uploads.mangadex.org", "cdn.thrive.moe")
            .let { "$it.$size.jpg" }
    
    override suspend fun resolveLink(link: HttpUrl): Manga? {
        if (link.host != domain) return null
        val segments = link.pathSegments.filter { it.isNotBlank() }
        val id = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return when (segments.firstOrNull()) {
            "title" -> fetchDetails(id, stubManga(id))
            "read" -> resolveChapterToManga(id)
            else -> null
        }
    }

    private fun stubManga(mangaId: String): Manga = Manga(
        id = generateUid(mangaId),
        url = "/title/$mangaId",
        publicUrl = "$siteUrl/title/$mangaId",
        title = "",
        coverUrl = null,
        altTitles = emptySet(),
        rating = RATING_UNKNOWN,
        contentRating = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
    )

    private suspend fun resolveChapterToManga(chapterId: String): Manga? {
        val doc = runCatching {
            webClient.httpGet("$siteUrl/read/$chapterId", getRequestHeaders())
                .parseHtml()
        }.getOrNull() ?: return null

        val mangaHref = doc.selectFirst("a[href^=/title/]")?.attr("href")
            ?: return null
        val mangaId = mangaHref.removePrefix("/title/").trim('/')
            .takeIf { it.isNotBlank() } ?: return null

        return fetchDetails(mangaId, stubManga(mangaId))
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast('/')
        if (chapterId.isBlank()) return emptyList()

        val doc = runCatching {
            webClient.httpGet("$siteUrl/read/$chapterId", getRequestHeaders())
                .parseHtml()
        }.getOrNull() ?: return emptyList()

        val imageUrls = mutableListOf<String>()
        doc.select("img").forEach { img ->
            val src = img.absUrl("src")
                .ifBlank { img.absUrl("data-src") }
                .ifBlank { img.attr("data-src") }
            if (src.isBlank()) return@forEach
            val isPageImage = src.contains("/data/") && (
                    src.contains("thrive.moe") ||
                            src.contains("mangadex.org")
                    )
            if (isPageImage && src !in imageUrls) imageUrls.add(src)
        }

        if (imageUrls.isEmpty()) {
            val root = doc.selectFirst("script#__NEXT_DATA__")?.data()
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val props = root?.optJSONObject("props")?.optJSONObject("pageProps")
            for (key in listOf("images", "pages", "imageUrls")) {
                val arr = props?.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }
                        ?.let { if (it !in imageUrls) imageUrls.add(it) }
                }
                if (imageUrls.isNotEmpty()) break
            }
        }

        return imageUrls.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}
