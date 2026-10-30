package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.UserAgents

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
import tsuki.util.parseJson
import tsuki.util.parseRaw
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

import java.text.SimpleDateFormat
import java.util.Base64
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("MGREADIO", "Mgread.io", "en")
internal class MgreadIo(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MGREADIO, 24) {

    override val configKeyDomain = ConfigKey.Domain("mgread.io")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Referer", "https://$domain/")
            .header("Origin", "https://$domain")
            .build()
        return chain.proceed(request)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_TODAY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.NEWEST_ASC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isTagsExclusionSupported = false,
        isMultipleTagsSupported = true,
        isOriginalLocaleSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentRating = EnumSet.of(
            ContentRating.SAFE,
            ContentRating.SUGGESTIVE,
            ContentRating.ADULT,
        ),
    )

    private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private val restChapterDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT+7")
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }

        if (query != null) {
            val url = "https://$domain/wp-json/initlise/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", query)
                .addQueryParameter("page", page.toString())
                .build()
            val jsonArray = JSONArray(webClient.httpGet(url).parseRaw())
            return (0 until jsonArray.length()).mapNotNull { i ->
                val obj = jsonArray.getJSONObject(i)
                // Search returns non-manga post types too (stickers); filter by post_type.
                if (obj.optString("post_type") != "manga") return@mapNotNull null
                val title = Jsoup.parse(obj.optString("title")).text()
                val mangaUrl = obj.optString("url").trim().takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val thumb = obj.optString("thumb").takeIf(String::isNotEmpty)
                Manga(
                    id = generateUid(mangaUrl),
                    title = title,
                    url = mangaUrl.toHttpUrl().encodedPath,
                    publicUrl = mangaUrl.toAbsoluteUrl(domain),
                    coverUrl = thumb,
                    source = source,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                )
            }.filterNot { it.isAnime() }
        }

        val url = buildAdvancedFilterUrl(page, order, filter)
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaGrid(doc).filterNot { it.isAnime() }
    }

    private fun buildAdvancedFilterUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val builder = "https://$domain/advanced-filter".toHttpUrl().newBuilder()
        if (page > 1) {
            builder.addPathSegment("page")
            builder.addPathSegment(page.toString())
        }

        val sortParam = when (order) {
            SortOrder.UPDATED -> "updated"
            SortOrder.POPULARITY -> "views"
            SortOrder.POPULARITY_TODAY -> "views_day"
            SortOrder.POPULARITY_WEEK -> "views_week"
            SortOrder.POPULARITY_MONTH -> "view_month"
            SortOrder.RATING -> "rating"
            SortOrder.NEWEST -> "new"
            SortOrder.NEWEST_ASC -> "old"
            else -> "updated"
        }
        builder.addQueryParameter("sort", sortParam)

        filter.states.firstOrNull()?.let { state ->
            val statusParam = when (state) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "completed"
                MangaState.PAUSED -> "season_end"
                MangaState.ABANDONED -> "dropped"
                else -> null
            }
            statusParam?.let { builder.addQueryParameter("status", it) }
        }

        filter.contentRating.firstOrNull()?.let { cr ->
            val ageParam = when (cr) {
                ContentRating.SAFE -> "all"
                ContentRating.SUGGESTIVE -> "16+"
                ContentRating.ADULT -> "18+"
            }
            builder.addQueryParameter("age_rating", ageParam)
        }

        filter.tags.forEach { tag ->
            builder.addQueryParameter("genre[]", tag.key)
        }

        return builder.build().toString()
    }
    
    private fun parseMangaGrid(doc: Document): List<Manga> {
        return doc.select(
            "div.manga-card, div.manga-item-grid > div.uk-panel, div.manga-item-details",
        ).mapNotNull { element ->
            val titleElement = element.selectFirst("h2 a[href*='/manga/']")
                ?: element.selectFirst("h3 a[href*='/manga/']")
                ?: element.selectFirst("a[href*='/manga/']:not([href*='/chapter-'])")
                ?: return@mapNotNull null
            val href = titleElement.absUrl("href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Manga(
                id = generateUid(href),
                title = titleElement.text().trim(),
                url = href.toHttpUrl().encodedPath,
                publicUrl = href,
                coverUrl = element.selectFirst("img")?.imageUrl(),
                source = source,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
            )
        }
    }

    private fun Manga.isAnime(): Boolean = url.substringAfter("/manga/", "").startsWith("anime-")

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst("h1")?.ownText()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" [Ch.")?.trim()
            ?: manga.title

        val cover = doc.selectFirst("a.story-cover img")?.imageUrl()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: manga.coverUrl

        val descriptionText = doc.selectFirst("div#manga-description")?.let { element ->
            element.select("p, h1, h2, h3, h4, h5, h6, li")
                .ifEmpty { listOf(element) }
                .mapNotNull { it.textOrNull() }
                .joinToString("\n\n")
                .takeIf { it.isNotBlank() }
        }
        val altTitle = doc.selectFirst("span#comic-othername")?.textOrNull()

        val statusKey = doc.selectFirst("div.story-cover-wrap span.manga-status-ribbon")
            ?.classNames()?.firstOrNull { it.startsWith("ribbon-") }
            ?.removePrefix("ribbon-")
            ?: doc.selectFirst("#manga-status")?.text()?.lowercase(Locale.US)
                ?.replace(' ', '_')
        val state = when (statusKey) {
            "ongoing", "caught_up" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "season_end", "source_hiatus" -> MangaState.PAUSED
            "dropped" -> MangaState.ABANDONED
            else -> null
        }

        val tags = doc.select("div#genre-tags a:not(.uk-disabled)").mapNotNullTo(mutableSetOf()) { a ->
            val name = a.ownText().trim().takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
            MangaTag(
                title = name,
                key = name.lowercase(Locale.US).replace(" ", "-"),
                source = source,
            )
        }

        val mangaId = doc.selectFirst("#manga-title[data-id]")?.attr("data-id")
            ?.toIntOrNull()
            ?: doc.selectFirst("#chapter-search-input[data-manga-id]")
                ?.attr("data-manga-id")?.toIntOrNull()

        val chapters = if (mangaId != null) {
            fetchChaptersApi(mangaId, manga.url)
        } else {
            doc.select(".chapter-list .chapter-item").mapNotNull { chapterFromElement(it) }.sortedBy { it.number }
        }

        val description = buildString {
            if (!descriptionText.isNullOrBlank()) append(descriptionText)
            if (!altTitle.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative title: ").append(altTitle)
            }
        }.trim().takeIf { it.isNotEmpty() }

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            tags = tags,
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun fetchChaptersApi(mangaId: Int, mangaPath: String): List<MangaChapter> {
        val result = mutableListOf<MangaChapter>()
        val cleanPath = mangaPath.substringBefore("/chapter/").trimEnd('/')

        var page = 1
        var totalPages: Int
        do {
            val url = "https://$domain/wp-json/initmanga/v1/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("manga_id", mangaId.toString())
                .addQueryParameter("per_page", "50")
                .addQueryParameter("paged", page.toString())
                .build()
            val json = JSONObject(webClient.httpGet(url).parseRaw())
            val items = json.optJSONArray("items") ?: break
            totalPages = json.optInt("total_pages", 1)

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val number = item.optDouble("number", -1.0).toFloat()
                if (number < 0f) continue

                val rawTitle = item.optString("title").trim()
                val slug = item.optString("slug").trim()
                if (slug.isEmpty()) continue

                val createdAt = item.optString("created_at")
                val isLocked = item.optString("lock_type", "none") != "none" &&
                        !item.optBoolean("is_purchased", false)

                val chapterName = buildString {
                    if (isLocked) append("🔒 ")
                    val lower = rawTitle.lowercase(Locale.US)
                    if (lower.startsWith("chapter") || lower.startsWith("bölüm")) {
                        append(rawTitle)
                    } else {
                        append("Chapter ${number.toInt()}")
                        if (rawTitle.isNotEmpty()) append(" - ").append(rawTitle)
                    }
                }

                result.add(
                    MangaChapter(
                        id = generateUid("$cleanPath/$slug/"),
                        title = chapterName,
                        number = number,
                        url = "$cleanPath/$slug/",
                        uploadDate = restChapterDateFormat.parseSafe(createdAt),
                        source = source,
                        volume = 0,
                        scanlator = null,
                        branch = null,
                    ),
                )
            }
            page++
        } while (page <= totalPages)

        return result.reversed()
    }

    private fun chapterFromElement(element: Element): MangaChapter? {
        val urlElement = element.selectFirst("a[href*='/chapter-']") ?: return null
        val chapterUrl = urlElement.absUrl("href").takeIf { it.isNotBlank() } ?: return null
        val path = chapterUrl.toHttpUrl().encodedPath
        val name = element.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
            ?: chapterUrl.substringBeforeLast('/').substringAfterLast('/')
                .replace('-', ' ').replaceFirstChar { it.uppercase() }
        val number = Regex("""/chapter-(\d+(?:\.\d+)?)/""")
            .find(chapterUrl)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        val dateStr = element.selectFirst("time[datetime]")?.attr("datetime")
        return MangaChapter(
            id = generateUid(path),
            title = name,
            number = number,
            url = path,
            uploadDate = dateStr?.parseChapterDate() ?: 0L,
            source = source,
            volume = 0,
            scanlator = null,
            branch = null,
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl.toHttpUrl()).parseHtml()

        if (doc.selectFirst("div.lock-card") != null) return emptyList()

        val scripts = doc.select("script").map { script ->
            script.attr("src").substringAfter("base64,", "")
                .takeIf { it.isNotEmpty() }
                ?.let { Base64.getDecoder().decode(it).decodeToString() }
                ?: script.data()
        }

        val payloadScript = scripts.firstOrNull { "\"ciphertext\"" in it }
            ?: return plainImages(doc)

        val payloadJson = JSONObject(payloadScript.objectLiteral())
        val ciphertext = payloadJson.getString("ciphertext")
        val iv = payloadJson.getString("iv").decodeHex()
        val salt = payloadJson.optString("salt").takeIf { it.isNotEmpty() && !payloadJson.isNull("salt") }

        val key = if (salt != null) {
            val dkJson = JSONObject(
                scripts.first { "\"decryption_key\"" in it }.objectLiteral(),
            )
            val passphrase = String(Base64.getDecoder().decode(dkJson.getString("decryption_key")))
            deriveKey(passphrase, salt.decodeHex())
        } else {
            fetchChapterKey(
                cid = payloadJson.getLong("cid"),
                epoch = payloadJson.getLong("e"),
                grant = payloadJson.getString("g"),
                chapterUrl = chapterUrl,
            )
        }

        val plain = decryptAesCbc(key, iv, Base64.getDecoder().decode(ciphertext))
        val contentDoc = Jsoup.parseBodyFragment(plain, "https://$domain/")
        return contentDoc.select("img, canvas[data-enc]").mapIndexedNotNull { i, element ->
            val imgUrl = when (element.tagName()) {
                "canvas" -> {
                    val bytes = Base64.getDecoder().decode(element.attr("data-enc"))
                    String(bytes.reversedArray())
                }
                else -> element.imageUrl()
            } ?: return@mapIndexedNotNull null
            MangaPage(
                id = generateUid("$i|$imgUrl"),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun plainImages(doc: Document): List<MangaPage> =
        doc.select("#chapter-content img").mapIndexedNotNull { i, img ->
            val imgUrl = img.imageUrl() ?: return@mapIndexedNotNull null
            MangaPage(
                id = generateUid("$i|$imgUrl"),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    
    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val doc = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("div.manga-item-slider").mapNotNull { card ->
            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val manga = Manga(
                id = generateUid(href),
                title = title,
                url = href.toHttpUrl().encodedPath,
                publicUrl = href,
                coverUrl = card.selectFirst("img")?.imageUrl(),
                source = source,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
            )
            manga.takeIf { !it.isAnime() }
        }
    }
    
    private fun Element.imageUrl(): String? = when (normalName()) {
        "meta" -> attr("content").takeIf { it.isNotBlank() }
        else -> sequenceOf("data-original-src", "data-src", "data-lazy-src", "src")
            .map { absUrl(it) }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
    }

    private fun String.objectLiteral(): String =
        substring(indexOf('{'), lastIndexOf('}') + 1)

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        // PBKDF2WithHmacSHA512, 999 iterations, 256-bit key.
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(spec).encoded
    }

    private fun decryptAesCbc(key: ByteArray, iv: ByteArray, data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data).decodeToString()
    }

    private suspend fun fetchChapterKey(
        cid: Long,
        epoch: Long,
        grant: String,
        chapterUrl: String,
    ): ByteArray {
        val response = webClient.httpPost(
            url = "https://$domain/wp-json/initmanga/v1/chapter-key".toHttpUrl(),
            body = JSONObject().apply {
                put("chapter_id", cid)
                put("epoch", epoch)
                put("grant", grant)
            },
            extraHeaders = Headers.Builder()
                .set("Referer", chapterUrl)
                .set("Origin", "https://$domain")
                .build(),
        )
        return response.parseJson().getString("key").decodeHex()
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun SimpleDateFormat.parseSafe(date: String): Long =
        runCatching { parse(date)?.time ?: 0L }.getOrDefault(0L)

    private fun String?.parseChapterDate(): Long {
        if (isNullOrBlank()) return 0L
        val normalized = this
            .replace(Regex("""([+-]\d{2}):(\d{2})$"""), "$1$2")
            .replace(Regex("""Z$"""), "+0000")
        return chapterDateFormat.parseSafe(normalized)
    }

    private fun fetchTags(): Set<MangaTag> =
        GENRES.map { (name, slug) ->
            MangaTag(title = name, key = slug, source = source)
        }.toSet()

    private companion object {
        private val GENRES = listOf(
            "Action" to "action",
            "Adaptation" to "adaptation",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Cooking" to "cooking",
            "Crime" to "crime",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Fantasy" to "fantasy",
            "Harem" to "harem",
            "Historical" to "historical",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "Martial Arts" to "martial-arts",
            "Mature" to "mature",
            "Mecha" to "mecha",
            "Medical" to "medical",
            "Music" to "music",
            "Mystery" to "mystery",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Slice of life" to "slice-of-life",
            "Smut" to "smut",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
            "Webtoons" to "webtoons",
        )
    }
}
