package tsuki.site.ar

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
import tsuki.util.parseJson

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.EnumSet

@MangaSourceParser("MANGADAR", "MangaDar", "ar")
internal class MangaDar(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGADAR, 20) {

    override val configKeyDomain = ConfigKey.Domain("mangadar.com")
    private val baseUrl = "https://$domain"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
            availableTags = GENRES.mapTo(mutableSetOf()) { (name, slug) ->
                MangaTag(key = slug, title = name, source = source)
            },
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (filter.query.orEmpty().trim().isNotEmpty()) {
            if (page > 1) return emptyList()
            val url = "$baseUrl/wp-admin/admin-ajax.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "mangaverse_search")
                .addQueryParameter("q", filter.query.orEmpty().trim())
                .build()
            val json = webClient.httpGet(url).parseJson()
            return parseSearchJson(json)
        }

        val urlBuilder = "$baseUrl/manga/".toHttpUrl().newBuilder()
        if (page > 1) {
            urlBuilder.addPathSegment("page").addPathSegment(page.toString())
        }

        when (order) {
            SortOrder.POPULARITY -> urlBuilder.addQueryParameter("sort", "popular")
            SortOrder.UPDATED -> urlBuilder.addQueryParameter("sort", "new")
            else -> Unit
        }

        filter.types.firstOrNull()?.let { type ->
            val typeParam = when (type) {
                ContentType.MANGA -> "manga"
                ContentType.MANHWA -> "manhwa"
                ContentType.MANHUA -> "manhua"
                else -> null
            }
            if (typeParam != null) urlBuilder.addQueryParameter("type", typeParam)
        }

        filter.states.firstOrNull()?.let { state ->
            val stateParam = when (state) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "completed"
                else -> null
            }
            if (stateParam != null) urlBuilder.addQueryParameter("status", stateParam)
        }

        filter.tags.firstOrNull()?.let { tag ->
            urlBuilder.addQueryParameter("genre", tag.key)
        }

        val doc = webClient.httpGet(urlBuilder.build()).parseHtml()
        return parseMangaListPage(doc)
    }

    private fun parseSearchJson(json: JSONObject): List<Manga> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<Manga>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
            val itemUrl = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val slug = runCatching {
                itemUrl.toHttpUrl().pathSegments.last { it.isNotEmpty() }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val cover = item.optString("cover").takeIf { it.isNotBlank() }

            result += Manga(
                id = generateUid(slug),
                url = slug,
                publicUrl = "$baseUrl/manga/$slug",
                title = title,
                altTitles = emptySet(),
                coverUrl = cover,
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
        return result
    }

    private fun parseMangaListPage(doc: Document): List<Manga> =
        doc.select("a[href*=/manga/]").mapNotNull { element ->
            val href = element.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val segments = runCatching {
                href.toHttpUrl().pathSegments.filter { it.isNotEmpty() }
            }.getOrNull() ?: return@mapNotNull null
            if (segments.size != 2 || segments[0] != "manga") return@mapNotNull null
            val slug = segments[1]
            if (slug.isBlank() || slug == "page") return@mapNotNull null

            val title = element.select("img").attr("alt")
                .ifBlank { element.select("h3, h4, .font-semibold").text() }
            if (title.isBlank()) return@mapNotNull null

            val thumbnail = element.select("img").let { img ->
                img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
                    .takeIf { it.isNotBlank() }
            }

            Manga(
                id = generateUid(slug),
                url = slug,
                publicUrl = "$baseUrl/manga/$slug",
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
        }.distinctBy { it.url }
    
    
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet("$baseUrl/manga/${manga.url}".toHttpUrl()).parseHtml()

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: manga.title

        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("[x-ref=synContent]")?.let { syn ->
                syn.select("p").joinToString("\n\n") { it.text().trim() }
                    .takeIf { it.isNotBlank() }
            }
            ?: doc.selectFirst(".summary__content, .description-summary")?.let { syn ->
                syn.select("p").joinToString("\n\n") { it.text().trim() }
                    .takeIf { it.isNotBlank() }
            }
            ?: doc.selectFirst("p.text-neutral-400")?.text()?.takeIf { it.isNotBlank() }

        val pageText = doc.text()
        val state = when {
            pageText.contains("مستمر") -> MangaState.ONGOING
            pageText.contains("مكتمل") -> MangaState.FINISHED
            else -> null
        }

        val tags = doc.select("a[href*=/genre/]").mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val slug = runCatching {
                href.toHttpUrl().pathSegments.lastOrNull { it.isNotEmpty() }
            }.getOrNull() ?: return@mapNotNull null
            MangaTag(key = slug, title = link.text(), source = source)
        }.distinctBy { it.key }.toSet()

        var author: String? = null
        var artist: String? = null
        doc.select("aside div:has(> span)").forEach { row ->
            val spans = row.select("span")
            if (spans.size >= 2) {
                val label = spans[0].text()
                val value = spans[1].text()
                when {
                    label.contains("المؤلف") -> author = value
                    label.contains("الرسام") -> artist = value
                }
            }
        }
        if (author.isNullOrBlank()) {
            doc.selectFirst("a[href*=/people/author/]")
                ?.text()?.takeIf { it.isNotBlank() }
                ?.let { author = it }
        }
        
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("picture img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".summary_image img")?.attr("abs:src")?.takeIf { it.isNotBlank() }

        val chapters = parseChaptersFromDoc(doc, manga.url)

        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            state = state,
            tags = tags,
            authors = setOfNotNull(author, artist),
            chapters = chapters,
        )
    }
    
    private fun parseChaptersFromDoc(doc: Document, mangaSlug: String): List<MangaChapter> {
        doc.selectFirst("script#mdg-manga-chapters-json")?.let { tag ->
            val raw = tag.data().ifBlank { tag.html() }.trim()
            if (raw.isNotEmpty()) {
                val rows = runCatching { JSONObject(raw).optJSONArray("rows") }.getOrNull()
                if (rows != null) {
                    val parsed = parseRowsJson(rows, mangaSlug)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }

        for (container in doc.select("div[x-data]")) {
            val xData = container.attr("x-data")
            val rowsStart = xData.indexOf("rows:")
            if (rowsStart == -1) continue
            val bracketStart = xData.indexOf("[", rowsStart)
            if (bracketStart == -1) continue
            val bracketEnd = findMatchingBracket(xData, bracketStart)
            if (bracketEnd == -1) continue
            val rowsStr = xData.substring(bracketStart, bracketEnd + 1)
            val rows = runCatching { JSONArray(rowsStr) }.getOrNull() ?: continue
            val parsed = parseRowsJson(rows, mangaSlug)
            if (parsed.isNotEmpty()) return parsed
        }

        return parseMadaraChapters(doc, mangaSlug).reversed()
    }
    
    private fun parseRowsJson(rows: JSONArray, mangaSlug: String): List<MangaChapter> {
        val result = mutableListOf<MangaChapter>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            if (row.length() < 4) continue

            val chapterNumber = row.optString(1)
            val chapterUrl = row.optString(2)
            val timestamp = row.optLong(3, 0L)
            if (chapterUrl.isBlank()) continue

            val relativeUrl = runCatching {
                chapterUrl.toHttpUrl().encodedPath
            }.getOrNull() ?: chapterUrl

            result += MangaChapter(
                id = generateUid("$mangaSlug|$relativeUrl"),
                url = relativeUrl,
                title = "الفصل $chapterNumber",
                number = chapterNumber.toFloatOrNull() ?: 0f,
                volume = 0,
                branch = null,
                uploadDate = timestamp * 1000L,
                scanlator = null,
                source = source,
            )
        }
        return result
    }
    
    private fun parseMadaraChapters(doc: Document, mangaSlug: String): List<MangaChapter> {
        val result = mutableListOf<MangaChapter>()
        for (li in doc.select("li.wp-manga-chapter")) {
            val a = li.selectFirst("a[href]") ?: continue
            val href = a.absUrl("href").ifBlank { a.attr("href") }
                .takeIf { it.isNotBlank() } ?: continue
            val relativeUrl = runCatching { href.toHttpUrl().encodedPath }.getOrNull() ?: href
            val text = a.text().trim()
            if (text.isBlank()) continue

            val number = Regex("""\d+(?:\.\d+)?""").find(text)?.value?.toFloatOrNull() ?: 0f
            val dateText = li.selectFirst(".chapter-release-date i")?.text()
            val uploadDate = parseArabicDate(dateText)

            result += MangaChapter(
                id = generateUid("$mangaSlug|$relativeUrl"),
                url = relativeUrl,
                title = "الفصل $text",
                number = number,
                volume = 0,
                branch = null,
                uploadDate = uploadDate,
                scanlator = null,
                source = source,
            )
        }
        return result
    }

    private fun findMatchingBracket(s: String, startIndex: Int): Int {
        if (startIndex < 0 || startIndex >= s.length) return -1
        var depth = 0
        for (i in startIndex until s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
    
    private fun parseArabicDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val months = mapOf(
            "يناير" to 1, "فبراير" to 2, "مارس" to 3, "أبريل" to 4,
            "مايو" to 5, "يونيو" to 6, "يوليو" to 7, "أغسطس" to 8,
            "سبتمبر" to 9, "أكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12,
        )
        val match = Regex("""(\S+)\s+(\d+),\s*(\d{4})""").find(text) ?: return 0L
        val month = months[match.groupValues[1]] ?: return 0L
        val day = match.groupValues[2].toIntOrNull() ?: return 0L
        val year = match.groupValues[3].toIntOrNull() ?: return 0L
        return runCatching {
            java.time.LocalDate.of(year, month, day)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = if (chapter.url.startsWith("http")) {
            chapter.url.toHttpUrl()
        } else {
            (baseUrl + chapter.url).toHttpUrl()
        }
        val doc = webClient.httpGet(url).parseHtml()
        
        doc.selectFirst("script[id^=mv-pages-]")?.let { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isNotEmpty()) {
                val json = runCatching { JSONArray(raw) }.getOrNull()
                if (json != null && json.length() > 0) {
                    return (0 until json.length()).mapNotNull { i ->
                        val pageUrl = json.optString(i).takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        MangaPage(
                            id = generateUid("$i|$pageUrl"),
                            url = pageUrl,
                            preview = null,
                            source = source,
                        )
                    }
                }
            }
        }
        
        return doc.select(".reader-page img").mapIndexedNotNull { index, img ->
            val imageUrl = img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
                .takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            MangaPage(
                id = generateUid("$index|$imageUrl"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private companion object {
        private val GENRES = listOf(
            "أكشن" to "action",
            "مغامرة" to "adventure",
            "كوميديا" to "comedy",
            "دراما" to "drama",
            "خيال" to "fantasy",
            "رعب" to "horror",
            "رومانسي" to "romance",
            "شونين" to "shounen",
            "سينين" to "seinen",
            "مدرسي" to "school",
            "خارق للطبيعة" to "supernatural",
            "تاريخي" to "historical",
            "إيسيكاي" to "isekai",
            "تناسخ" to "reincarnation",
            "نفسي" to "psychological",
            "تشويق" to "suspense",
            "خيال حضري" to "urban-fantasy",
            "الفنون القتالية" to "martial-arts",
            "سفر عبر الزمن" to "time-travel",
            "غموض" to "mystery",
            "حائز على جوائز" to "award-winning",
            "شريرة" to "villainess",
            "خيال علمي" to "sci-fi",
            "بقاء" to "survival",
            "شريحة من الحياة" to "slice-of-life",
        )
    }
}
