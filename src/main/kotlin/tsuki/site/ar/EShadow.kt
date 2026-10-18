package tsuki.site.ar

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
import tsuki.util.parseJson

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.time.Instant
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("ESHADOW", "EShadow", "ar")
internal class EShadow(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ESHADOW, 20) {

    override val configKeyDomain = ConfigKey.Domain("shadowss.net")

    private val baseUrl get() = "https://$domain"

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "application/json")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$baseUrl/api/manga".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            filter.query?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("query", it.trim())
            }
        }.build()

        val json = webClient.httpGet(url, getRequestHeaders()).parseJson()
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            parseManga(item, rawRatingToKotatsu(item.optDouble("rating", 0.0)))
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url
        val json = runCatching {
            webClient.httpGet("$baseUrl/api/manga/$slug", getRequestHeaders()).parseJson()
        }.getOrNull() ?: return manga

        val detail = json.optJSONObject("manga") ?: return manga
        val uuid = detail.optString("id")

        val rating = fetchRating(uuid) ?: rawRatingToKotatsu(detail.optDouble("rating", 0.0))

        val base = parseManga(detail, rating) ?: return manga
        return base.copy(chapters = parseChapters(detail, slug))
    }

    private suspend fun fetchRating(mangaId: String): Float? {
        if (mangaId.isBlank()) return null
        val json = runCatching {
            webClient.httpGet("$baseUrl/api/ratings?mangaId=$mangaId", getRequestHeaders()).parseJson()
        }.getOrNull() ?: return null

        val count = json.optInt("count", 0)
        val average = json.optDouble("average", 0.0)
        if (count <= 0 || average <= 0.0) return null
        return (average / 10.0).toFloat().coerceIn(0f, 1f)
    }

    private fun parseChapters(detail: JSONObject, slug: String): List<MangaChapter> {
        val arr = detail.optJSONArray("chapters") ?: return emptyList()
        val chapters = ArrayList<MangaChapter>(arr.length())

        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i) ?: continue
            val id = ch.optString("id").takeIf { it.isNotBlank() } ?: continue
            val number = ch.optDouble("number", -1.0).toFloat()
            if (number < 0f) continue

            val rawTitle = ch.optString("title").takeIf { it.isNotBlank() }
            val displayName = buildString {
                append("الفصل ").append(formatNumber(number))
                if (rawTitle != null &&
                    rawTitle != "Chapter $number" &&
                    rawTitle != "Chapter ${formatNumber(number)}"
                ) {
                    append(" - ").append(rawTitle)
                }
            }

            chapters.add(
                MangaChapter(
                    id = generateUid(id),
                    url = "$slug|$id",
                    title = displayName,
                    number = number,
                    volume = 0,
                    uploadDate = parseDate(ch.optString("publishedAt")),
                    scanlator = null,
                    branch = null,
                    source = source,
                ),
            )
        }
        return chapters.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("|")
        if (parts.size != 2) return emptyList()
        val slug = parts[0]
        val chapterId = parts[1]

        val json = runCatching {
            webClient.httpGet("$baseUrl/api/manga/$slug", getRequestHeaders()).parseJson()
        }.getOrNull() ?: return emptyList()

        val detail = json.optJSONObject("manga") ?: return emptyList()
        val chapters = detail.optJSONArray("chapters") ?: return emptyList()

        for (i in 0 until chapters.length()) {
            val ch = chapters.optJSONObject(i) ?: continue
            if (ch.optString("id") != chapterId) continue
            val pages = ch.optJSONArray("pages") ?: return emptyList()

            return (0 until pages.length()).mapNotNull { idx ->
                val path = pages.optString(idx).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val url = absoluteUrl(path)
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }
        return emptyList()
    }

    private fun parseManga(json: JSONObject, rating: Float): Manga? {
        val uuid = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val slug = json.optString("slug").takeIf { it.isNotBlank() } ?: uuid
        val title = json.optString("title").takeIf { it.isNotBlank() } ?: return null

        val altTitles = buildSet {
            json.optJSONArray("altTitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        val authors = linkedSetOf<String>()
        json.optString("author")
            .takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?.let(authors::add)
        json.optString("artist")
            .takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?.let(authors::add)

        val tags = mutableSetOf<MangaTag>()
        json.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }
                    ?.let { tags.add(MangaTag(it, it, source)) }
            }
        }

        val coverPath = json.optString("cover").takeIf { it.isNotBlank() }

        return Manga(
            id = generateUid(uuid),
            url = slug,
            publicUrl = "$baseUrl/manga/$slug",
            title = title,
            coverUrl = coverPath?.let(::absoluteUrl),
            altTitles = altTitles,
            rating = rating,
            contentRating = null,
            tags = tags,
            state = when (json.optString("status").lowercase(Locale.ROOT)) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "hiatus" -> MangaState.PAUSED
                else -> null
            },
            authors = authors,
            description = json.optString("description").takeIf { it.isNotBlank() },
            source = source,
        )
    }

    private fun rawRatingToKotatsu(raw: Double): Float =
        if (raw > 0.0) (raw / 10.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN

    private fun absoluteUrl(path: String): String =
        if (path.startsWith("http")) path else "$baseUrl$path"

    private fun parseDate(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(str).toEpochMilli() }.getOrDefault(0L)
    }

    private fun formatNumber(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
