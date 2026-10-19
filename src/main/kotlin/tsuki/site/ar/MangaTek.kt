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
import tsuki.util.parseHtml
import tsuki.util.parseJson

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Instant
import java.util.EnumSet
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("MANGATEK", "MangaTek", "ar")
internal class MangaTek(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGATEK, 20) {

    override val configKeyDomain = ConfigKey.Domain("mangatek.com")

    private val baseUrl get() = "https://$domain"
    private val apiBaseUrl get() = "https://api.$domain"

    @Volatile
    private var tagsCache: Set<MangaTag> = emptySet()
    private val tagsMutex = Mutex()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = false,
    )

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .build()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = tagsMutex.withLock {
            if (tagsCache.isNotEmpty()) return@withLock tagsCache
            fetchTags().also { tagsCache = it }
        }
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        )
    }

    private suspend fun fetchTags(): Set<MangaTag> = runCatching {
        val json = webClient
            .httpGet("$apiBaseUrl/api/tags?limit=500", getRequestHeaders())
            .parseJson()
        val data = json.optJSONArray("data") ?: return@runCatching emptySet()
        buildSet {
            for (i in 0 until data.length()) {
                val tag = data.optJSONObject(i) ?: continue
                val name = tag.optString("name").takeIf { it.isNotBlank() } ?: continue
                if (tag.optInt("counter", 0) > 0) {
                    add(MangaTag(name, name, source))
                }
            }
        }
    }.getOrDefault(emptySet())
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$baseUrl/manga-list".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            when (order) {
                SortOrder.POPULARITY -> addQueryParameter("sort", "views")
                SortOrder.UPDATED -> addQueryParameter("sort", "latest")
                SortOrder.NEWEST -> addQueryParameter("sort", "created_at")
                SortOrder.RATING -> addQueryParameter("sort", "rating")
                else -> Unit
            }
            filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("search", it.trim()) }
            when (filter.states.firstOrNull()) {
                MangaState.ONGOING -> addQueryParameter("status", "ongoing")
                MangaState.FINISHED -> addQueryParameter("status", "completed")
                else -> Unit
            }
            filter.tags.firstOrNull()?.key?.let { addQueryParameter("tags", it) }
        }.build()

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return doc.select(".flex-grow .grid a").mapNotNull { element ->
            val absHref = element.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = absHref.toHttpUrl().encodedPath
            val title = element.selectFirst("h3")?.attr("title")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val cover = element.selectFirst("img")?.let { img ->
                when {
                    img.hasAttr("data-src") -> img.absUrl("data-src")
                    img.hasAttr("data-lazy-src") -> img.absUrl("data-lazy-src")
                    else -> img.absUrl("src")
                }
            }?.takeIf { it.isNotBlank() }

            Manga(
                id = generateUid(path),
                url = path,
                publicUrl = absHref,
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
    }
    
    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = "$baseUrl${manga.url}"
        val doc = runCatching { webClient.httpGet(fullUrl, getRequestHeaders()).parseHtml() }
            .getOrNull() ?: return manga

        val wrapper = extractAstroProp(doc, "manga") ?: return manga
        val data = wrapper.optJSONObject("manga") ?: wrapper
        val slug = manga.url.split("/").getOrNull(2) ?: return manga

        return applyDetails(manga, data, slug)
    }

    private fun applyDetails(manga: Manga, data: JSONObject, slug: String): Manga {
        val title = data.optString("title").ifBlank { manga.title }
        val cover = data.optString("cover_image").takeIf { it.isNotBlank() } ?: manga.coverUrl
        val description = data.optString("description").takeIf { it.isNotBlank() }
        val author = data.optString("author").takeIf {
            it.isNotBlank() && !it.equals("unknown", ignoreCase = true)
        }
        val state = when (data.optString("status").lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled", "dropped" -> MangaState.ABANDONED
            else -> null
        }

        val tags = mutableSetOf<MangaTag>()
        data.optJSONArray("Tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { tags.add(MangaTag(it, it, source)) }
            }
        }

        val chapters = mutableListOf<MangaChapter>()
        data.optJSONArray("MangaChapters")?.let { arr ->
            for (i in 0 until arr.length()) {
                val ch = arr.optJSONObject(i) ?: continue
                val number = ch.optString("chapter_number").trim()
                if (number.isBlank()) continue
                val chTitle = ch.optString("title")
                    .takeIf { it.isNotBlank() && it != number }
                    ?: "Chapter $number"
                val uploadDate = runCatching {
                    Instant.parse(ch.optString("created_at")).toEpochMilli()
                }.getOrDefault(0L)
                val readerUrl = "/reader/$slug/$number"
                chapters.add(
                    MangaChapter(
                        id = generateUid(readerUrl),
                        title = chTitle,
                        number = number.toFloatOrNull() ?: 0f,
                        volume = 0,
                        url = readerUrl,
                        uploadDate = uploadDate,
                        scanlator = null,
                        branch = null,
                        source = source,
                    ),
                )
            }
        }

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            authors = author?.let { setOf(it) } ?: emptySet(),
            tags = tags,
            state = state,
            chapters = chapters.sortedBy { it.number },
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = runCatching {
            webClient.httpGet("$baseUrl${chapter.url}", getRequestHeaders()).parseHtml()
        }.getOrNull() ?: return emptyList()

        val props = extractAstroProp(doc, "imageUrls") ?: return emptyList()

        val imageUrls = mutableListOf<String>()
        props.optJSONArray("imageUrls")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(imageUrls::add)
            }
        }
        if (imageUrls.isEmpty()) return emptyList()

        val overlayBlob = props.optString("overlayBlob").takeIf { it.isNotBlank() }
        val chapterId = props.optLong("chapterId", -1L).takeIf { it > 0 }
        val unlockToken = props.optString("unlockToken").takeIf { it.isNotBlank() }

        runCatching {
            when {
                overlayBlob != null -> decryptOverlay(overlayBlob)
                chapterId != null && unlockToken != null -> unlockOverlay(chapterId, unlockToken)?.first
                else -> null
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
    
    private fun decryptOverlay(blob: String, keyHex: String = DEFAULT_KEY): JSONObject? = runCatching {
        val parts = blob.split(":")
        if (parts.size != 3) return@runCatching null
        val iv = parts[0].hexToBytes()
        val ciphertext = parts[1].hexToBytes()
        val tag = parts[2].hexToBytes()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyHex.hexToBytes(), "AES"),
            GCMParameterSpec(tag.size * 8, iv),
        )
        JSONObject(String(cipher.doFinal(ciphertext + tag), Charsets.UTF_8))
    }.getOrNull()

    private fun unlockOverlay(chapterId: Long, unlockToken: String): Pair<JSONObject, Int?>? {
        val proof = sha256Hex("$UNLOCK_PROOF_SALT|$unlockToken|$chapterId")
        val body = JSONObject().apply {
            put("chapterId", chapterId)
            put("token", unlockToken)
            put("proof", proof)
        }
        val json = runCatching {
            val req = Request.Builder()
                .url(UNLOCK_API_URL)
                .headers(getRequestHeaders())
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            context.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: "")
            }
        }.getOrNull() ?: return null

        val overlay = json.optString("overlay").takeIf { it.isNotBlank() } ?: return null
        val key = json.optString("key").takeIf { it.isNotBlank() } ?: DEFAULT_KEY
        val offset = json.optInt("overlay_page_offset", -1).takeIf { it >= 0 }
        val decrypted = decryptOverlay(overlay, key) ?: return null
        return decrypted to offset
    }
    
    private fun extractAstroProp(doc: Document, key: String): JSONObject? {
        val raw = doc.selectFirst("[props*=$key]")?.attr("props") ?: return null
        return runCatching {
            when (val unwrapped = unwrapAstro(JSONObject(raw))) {
                is JSONObject -> unwrapped
                is JSONArray -> (0 until unwrapped.length()).firstNotNullOfOrNull { unwrapped.optJSONObject(it) }
                else -> null
            }
        }.getOrNull()
    }

    private fun unwrapAstro(node: Any?): Any? = when (node) {
        is JSONObject -> JSONObject().apply {
            node.keys().forEach { k -> put(k, unwrapAstro(node.opt(k))) }
        }
        is JSONArray -> {
            if (node.length() == 2 && isPrimitive(node.opt(0))) {
                unwrapAstro(node.opt(1))
            } else {
                JSONArray().apply {
                    for (i in 0 until node.length()) put(unwrapAstro(node.opt(i)))
                }
            }
        }
        else -> node
    }

    private fun isPrimitive(v: Any?): Boolean = v is String || v is Number || v is Boolean
    
    private fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0) { "hex string length must be even" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val DEFAULT_KEY =
            "ff453871399fe268588a0936b45376022d85ed0fd1292001d5102f6a30291dc1"
        private const val UNLOCK_PROOF_SALT =
            "80c55d2d1f1432551b82f5304b60e1aced87f823a6ed309d"
        private const val UNLOCK_API_URL = "https://api.mangatek.com/api/reader/unlock"
    }
}
