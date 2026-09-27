package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.CommonHeaders

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

import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.json.extractNextJs
import tsuki.util.parseHtml
import tsuki.util.parseSafe
import tsuki.util.toAbsoluteUrl

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("TEMPLESCAN", "Temple Scan", "en", ContentType.HENTAI)
internal class TempleScan(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.TEMPLESCAN, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("templetoons.com")

    override fun getRequestHeaders(): Headers =
        super.getRequestHeaders().newBuilder()
            .set(CommonHeaders.REFERER, "https://$domain/")
            .set(CommonHeaders.ORIGIN, "https://$domain")
            .set(CommonHeaders.USER_AGENT, USER_AGENT)
            .set(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set(CommonHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
            .set(CommonHeaders.SEC_FETCH_DEST, "document")
            .set(CommonHeaders.SEC_FETCH_MODE, "navigate")
            .set(CommonHeaders.SEC_FETCH_SITE, "none")
            .set(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
            .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = emptySet(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val allSeries = fetchCatalog()

        val statusFilter = filter.states.firstOrNull()
        val query = filter.query?.trim()?.takeIf(String::isNotBlank)

        val filtered = allSeries.filter { series ->
            val matchesQuery = query == null ||
                    series.title.contains(query, ignoreCase = true) ||
                    (series.alternativeNames?.contains(query, ignoreCase = true) == true)
            val matchesStatus = statusFilter == null || series.status == stateToApi(statusFilter)
            matchesQuery && matchesStatus
        }

        val sorted = when (order) {
            SortOrder.UPDATED -> filtered.sortedByDescending { it.updated }
            SortOrder.NEWEST -> filtered.sortedByDescending { it.created }
            SortOrder.POPULARITY -> filtered.sortedByDescending { it.views }
            SortOrder.ALPHABETICAL -> filtered.sortedBy { it.title }
            else -> filtered
        }

        val startIndex = (page - 1) * pageSize
        if (startIndex >= sorted.size) return emptyList()
        val endIndex = minOf(startIndex + pageSize, sorted.size)

        return sorted.subList(startIndex, endIndex).map { item ->
            Manga(
                id = generateUid("/comic/${item.slug}"),
                url = "/comic/${item.slug}",
                publicUrl = "https://$domain/comic/${item.slug}",
                title = item.title,
                altTitles = emptySet(),
                coverUrl = item.thumbnail?.toAbsoluteUrl(domain),
                authors = emptySet(),
                state = item.status.toMangaState(),
                contentRating = null,
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/comic/")
        val doc = webClient.httpGet("https://$domain/comic/$slug").parseHtml()

        val seriesLd = doc.extractComicSeriesLd()
        val seriesData = doc.extractNextJs { it is JSONObject && it.has("seriesData") } as? JSONObject
        val catalogEntry = fetchCatalog().firstOrNull { it.slug == slug }

        val genres = seriesLd?.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()
        val adult = genres.any { it.equals("+18", ignoreCase = true) }

        val altName = seriesLd?.optString("alternateName")?.takeIf { it.isNotBlank() }
        val authorName = seriesLd?.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }

        val rawDescription = doc.synopsis()
            ?: seriesLd?.optString("description")?.takeIf { it.isNotBlank() }

        val description = buildString {
            append(rawDescription.orEmpty())
            if (altName != null) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Name: ").append(altName)
            }
        }

        val chapters = parseChapters(slug, seriesData)

        return manga.copy(
            title = seriesLd?.optString("name")?.takeIf { it.isNotBlank() }
                ?: catalogEntry?.title ?: slug,
            altTitles = altName?.let { setOf(it) } ?: emptySet(),
            coverUrl = seriesLd?.optString("image")?.takeIf { it.isNotBlank() }
                ?: catalogEntry?.thumbnail?.toAbsoluteUrl(domain)
                ?: manga.coverUrl,
            description = description,
            authors = authorName?.let { setOf(it) } ?: emptySet(),
            state = catalogEntry?.status.toMangaState(),
            contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
            tags = buildSet {
                catalogEntry?.badge?.let { add(MangaTag(it.lowercase(), it, source)) }
                if (adult) add(MangaTag("adult", "Adult", source))
                genres.filterNot { it.equals("+18", ignoreCase = true) }.forEach {
                    add(MangaTag(it.lowercase(), it, source))
                }
            },
            chapters = chapters,
        )
    }

    private fun parseChapters(slug: String, seriesDataWrapper: JSONObject?): List<MangaChapter> {
        val seriesData = seriesDataWrapper?.optJSONObject("seriesData") ?: return emptyList()
        val seasons = seriesData.optJSONArray(FIELD_SEASONS) ?: return emptyList()

        val chapters = mutableListOf<MangaChapter>()
        for (s in 0 until seasons.length()) {
            val arr = seasons.getJSONObject(s).optJSONArray(FIELD_CHAPTERS) ?: continue
            for (c in 0 until arr.length()) {
                val chap = arr.getJSONObject(c)
                if (chap.optInt(FIELD_PRICE, 0) > 0) continue

                val name = chap.optString(FIELD_CHAPTER_NAME).takeIf { it.isNotBlank() } ?: continue
                val chapSlug = chap.optString(FIELD_CHAPTER_SLUG).takeIf { it.isNotBlank() } ?: continue
                val title = chap.optString("chapter_title").takeIf { it.isNotBlank() }

                chapters += MangaChapter(
                    id = generateUid("$slug/$chapSlug"),
                    title = if (title != null) "$name: $title" else name,
                    number = name.extractChapterNumber(),
                    url = "/comic/$slug/$chapSlug",
                    uploadDate = parseIso(chap.optString("created_at", null)),
                    source = source,
                    volume = 0,
                    scanlator = null,
                    branch = null,
                )
            }
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet("https://$domain${chapter.url}")
        val obj = response.extractNextJs { it is JSONObject && it.has(FIELD_IMAGES) } as? JSONObject
            ?: return emptyList()
        val images = obj.optJSONArray(FIELD_IMAGES) ?: return emptyList()
        return (0 until images.length()).map { i ->
            val url = images.getString(i)
            MangaPage(
                id = generateUid(url),
                url = url.toAbsoluteUrl(domain),
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchCatalog(): List<SeriesItem> {
        val response = webClient.httpGet("https://$domain/comics")
        val array = response.extractNextJs {
            it is JSONArray && it.length() > 0 && it.optJSONObject(0)?.has(FIELD_SLUG) == true
        } as? JSONArray ?: return emptyList()

        return (0 until array.length()).map { array.getJSONObject(it).toSeriesItem() }
    }

    private fun JSONObject.toSeriesItem(): SeriesItem = SeriesItem(
        slug = getString(FIELD_SLUG),
        title = getString(FIELD_TITLE),
        alternativeNames = optString("alternative_names", null),
        thumbnail = optString("thumbnail", null),
        badge = optString("badge", null),
        status = optString("status", null),
        updated = parseIso(optString("update_chapter", null)),
        created = parseIso(optString("created_at", null)),
        views = optLong("total_views", 0),
    )

    private fun Document.extractComicSeriesLd(): JSONObject? =
        select("script[type=application/ld+json]").mapNotNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
        }.firstNotNullOfOrNull { root ->
            when {
                root.optString("@type") == "ComicSeries" -> root
                root.has("@graph") -> root.optJSONArray("@graph")?.let { graph ->
                    (0 until graph.length())
                        .mapNotNull { graph.optJSONObject(it) }
                        .firstOrNull { it.optString("@type") == "ComicSeries" }
                }
                else -> null
            }
        }

    private fun Document.synopsis(): String? =
        selectFirst("#series-synopsis-text")?.let { el ->
            val ps = el.select("p")
            if (ps.isNotEmpty()) ps.joinToString("\n\n") { it.text() } else el.text()
        }?.takeIf { it.isNotBlank() }

    private fun parseIso(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return DATE_FORMAT.parseSafe(date)
    }

    private fun String?.toMangaState(): MangaState? = when (this?.lowercase()) {
        "ongoing" -> MangaState.ONGOING
        "hiatus" -> MangaState.PAUSED
        "completed" -> MangaState.FINISHED
        "canceled", "dropped" -> MangaState.ABANDONED
        else -> null
    }

    private fun stateToApi(state: MangaState): String = when (state) {
        MangaState.ONGOING -> "Ongoing"
        MangaState.FINISHED -> "Completed"
        MangaState.PAUSED -> "Hiatus"
        MangaState.ABANDONED -> "Canceled"
        else -> ""
    }

    data class SeriesItem(
        val slug: String,
        val title: String,
        val alternativeNames: String?,
        val thumbnail: String?,
        val badge: String?,
        val status: String?,
        val updated: Long,
        val created: Long,
        val views: Long,
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        private const val FIELD_TITLE = "pnsk6q"
        private const val FIELD_SLUG = "s20a8oj"
        private const val FIELD_SEASONS = "u2ytwc"
        private const val FIELD_CHAPTERS = "qmy3ca"
        private const val FIELD_CHAPTER_NAME = "u171tuh"
        private const val FIELD_CHAPTER_SLUG = "y26ma5t"
        private const val FIELD_PRICE = "u1e8nmi"
        private const val FIELD_IMAGES = "nu7315"

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
    }
}
