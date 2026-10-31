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

import tsuki.util.decrypt.Bytes
import tsuki.util.decrypt.Bytes.decodeHex
import tsuki.util.decrypt.Bytes.readUShortBigEndian
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.json.isNullOrEmpty

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.EnumSet
import java.util.Locale

import tsuki.Broken
@Broken("chapter images, not sure if it still astro")
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
        
        val chapterId = props.optLong("chapterId", -1L).takeIf { it > 0 }
        val unlockToken = props.optString("unlockToken").takeIf { it.isNotBlank() }
        val mangaSlug = props.optString("mangaSlug").takeIf { it.isNotBlank() }
        val chapterNumber = props.optString("chapterNumber").takeIf { it.isNotBlank() }

        val imageUrls: List<String> =
            if (chapterId != null && unlockToken != null && mangaSlug != null && chapterNumber != null) {
                fetchAllPages(chapterId, mangaSlug, chapterNumber, unlockToken)
                    ?: extractImagesFromProps(props)
            } else {
                extractImagesFromProps(props)
            }
        if (imageUrls.isEmpty()) return emptyList()

        // Overlay handling (only present for `textMode == "overlay"` chapters)
        val overlayBlob = props.optString("overlayBlob").takeIf { it.isNotBlank() }
        val overlayPageOffset = props.optInt("overlayPageOffset", -1).takeIf { it >= 0 }
        val textMode = props.optString("textMode").takeIf { it.isNotBlank() }

        val overlayData: JSONObject? = when {
            overlayBlob != null -> jsonOrNull { OverlayCrypto.decryptHexBlob(overlayBlob) }
            textMode == "overlay" && chapterId != null && unlockToken != null ->
                unlockOverlay(chapterId, unlockToken)
            else -> null
        }

        // page_number counts from 1 after the overlayPageOffset images
        val firstOverlayImage = overlayPageOffset ?: 0
        val overlaysByImage: Map<Int, JSONArray> = overlayData?.let { data ->
            val pages = data.optJSONArray("pages") ?: JSONArray()
            buildMap {
                for (i in 0 until pages.length()) {
                    val page = pages.optJSONObject(i) ?: continue
                    val pageNumber = page.optInt("page_number", 0)
                    if (pageNumber <= 0) continue
                    val overlays = page.optJSONArray("overlays") ?: continue
                    if (overlays.length() == 0) continue
                    put(firstOverlayImage + pageNumber - 1, overlays)
                }
            }
        } ?: emptyMap()

        return imageUrls.mapIndexed { index, url ->
            val overlays = overlaysByImage[index]
            val finalUrl = if (overlays.isNullOrEmpty()) {
                url
            } else {
                url.toHttpUrl().newBuilder()
                    .fragment(overlays.toString())
                    .build()
                    .toString()
            }
            MangaPage(
                id = generateUid(finalUrl),
                url = finalUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun fetchAllPages(
        chapterId: Long,
        mangaSlug: String,
        chapterNumber: String,
        token: String,
    ): List<String>? = runCatching {
        val proof = OverlayCrypto.proof(UNLOCK_PROOF_SALT, token, chapterId)
        val body = JSONObject().apply {
            put("chapterId", chapterId)
            put("mangaSlug", mangaSlug)
            put("chapterNumber", chapterNumber)
            put("token", token)
            put("proof", proof)
        }
        val fingerprint = Bytes.toHex(Bytes.sha256(DEVICE_STRING.toByteArray(Charsets.UTF_8)))
        val req = Request.Builder()
            .url("$apiBaseUrl/api/reader/pages")
            .headers(getRequestHeaders())
            .header("x-device", DEVICE_STRING)
            .header("x-fp", fingerprint)
            .header("x-app-source", "mangatek")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        context.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val text = resp.body?.string() ?: return@runCatching null
            val json = JSONObject(text)
            if (!json.optBoolean("success", false)) return@runCatching null
            val arr = json.optJSONArray("data") ?: return@runCatching null
            val urls = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(urls::add)
            }
            urls.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
    
    
    private fun extractImagesFromProps(props: JSONObject): List<String> {
        val urls = mutableListOf<String>()
        props.optJSONArray("imageUrls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val element = arr.opt(i)
                val url = when (element) {
                    is String -> element
                    is JSONObject -> element.optString("url")
                    else -> null
                }
                url?.takeIf { it.isNotBlank() }?.let(urls::add)
            }
        }
        return urls
    }


    private suspend fun unlockOverlay(chapterId: Long, unlockToken: String): JSONObject? {
        val proof = OverlayCrypto.proof(UNLOCK_PROOF_SALT, unlockToken, chapterId)
        val esid = OverlayCrypto.esid(READER_SEED)
        val body = JSONObject().apply {
            put("chapterId", chapterId)
            put("token", unlockToken)
            put("proof", proof)
            put("esid", esid)
        }

        var response = postUnlock(body)
        if (response.optBoolean("needs_challenge", false)) {
            val nonce = response.optString("nonce").takeIf { it.isNotBlank() } ?: return null
            val powBits = response.optInt("pow_bits", 0)
            val powAnswer = OverlayCrypto.pow(nonce, powBits) ?: return null
            val retryBody = JSONObject(body.toString()).apply {
                put("nonce", nonce)
                put("pow", powAnswer)
            }
            response = postUnlock(retryBody)
        }

        val overlay = response.optString("overlay").takeIf { it.isNotBlank() } ?: return null
        val ks = response.optString("ks").takeIf { it.isNotBlank() }
        val key = response.optString("key").takeIf { it.isNotBlank() }

        return when {
            ks != null -> jsonOrNull {
                OverlayCrypto.decryptEnvelope(READER_SEED, overlay, ks, unlockToken, chapterId)
            }
            key != null -> jsonOrNull { OverlayCrypto.decryptHexBlob(overlay, key) }
            else -> null
        }
    }

    private fun postUnlock(body: JSONObject): JSONObject = runCatching {
        val req = Request.Builder()
            .url(UNLOCK_API_URL)
            .headers(getRequestHeaders())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        context.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching JSONObject()
            JSONObject(resp.body?.string() ?: "")
        }
    }.getOrDefault(JSONObject())

    private inline fun jsonOrNull(block: () -> String): JSONObject? = runCatching {
        JSONObject(block())
    }.getOrNull()


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
    
    private object OverlayCrypto {

        private const val KEY = "ff453871399fe268588a0936b45376022d85ed0fd1292001d5102f6a30291dc1"
        private const val POW_MAX_TRIES = 5_000_000L

        fun proof(proofSalt: String, token: String, chapterId: Long): String =
            Bytes.toHex(Bytes.sha256("$proofSalt|$token|$chapterId".toByteArray(Charsets.UTF_8)))

        fun esid(readerSeed: String): String =
            Bytes.toHex(Bytes.sha256("esid|$readerSeed".toByteArray(Charsets.UTF_8))).take(8)

        suspend fun pow(nonce: String, bits: Int): String? {
            if (bits <= 0) return null
            for (counter in 0L until POW_MAX_TRIES) {
                if (counter % 10_000 == 0L) currentCoroutineContext().ensureActive()
                val answer = counter.toString(36)
                if (leadingZeroBits(Bytes.sha256("$nonce:$answer".toByteArray(Charsets.UTF_8))) >= bits) {
                    return answer
                }
            }
            return null
        }

        // "ivHex:ctHex:tagHex", AES-GCM
        fun decryptHexBlob(blob: String, keyHex: String = KEY): String {
            val parts = blob.split(":")
            require(parts.size == 3) { "Unexpected overlay format" }
            val iv = parts[0].decodeHex()
            val ciphertext = parts[1].decodeHex()
            val tag = parts[2].decodeHex()
            return Bytes.aesGcmDecrypt(keyHex.decodeHex(), iv, ciphertext + tag, tag.size * 8).decodeToString()
        }

        // Version byte, then [tagLen u16][ivLen u16][tag][ct][iv] XORed with a SHA-256 keystream
        fun decryptEnvelope(
            readerSeed: String,
            overlay: String,
            ks: String,
            token: String,
            chapterId: Long,
        ): String {
            val bytes = Bytes.fromBase64Url(overlay)
            val version = bytes[0].toInt() and 0xFF
            require(version == 3) { "Unsupported overlay version $version" }

            val data = bytes.copyOfRange(1, bytes.size)
            for (start in data.indices step 32) {
                val block = Bytes.sha256("$readerSeed|v$version|$ks|${start / 32}".toByteArray(Charsets.UTF_8))
                for (i in start until minOf(start + 32, data.size)) {
                    data[i] = (data[i].toInt() xor block[i - start].toInt()).toByte()
                }
            }

            val tagLength = data.readUShortBigEndian(0)
            val ivLength = data.readUShortBigEndian(2)
            val tag = data.copyOfRange(4, 4 + tagLength)
            val ciphertext = data.copyOfRange(4 + tagLength, data.size - ivLength)
            val iv = data.copyOfRange(data.size - ivLength, data.size)

            val key = Bytes.sha256("$readerSeed|v$version|$ks|$token|$chapterId".toByteArray(Charsets.UTF_8))
            return Bytes.aesGcmDecrypt(key, iv, ciphertext + tag, tagLength * 8).decodeToString()
        }

        private fun leadingZeroBits(bytes: ByteArray): Int {
            val index = bytes.indexOfFirst { it.toInt() != 0 }
            if (index == -1) return bytes.size * 8
            return index * 8 + Integer.numberOfLeadingZeros(bytes[index].toInt() and 0xFF) - 24
        }
    }

    private companion object {
        private const val READER_SEED = "000000000000000000000000000000000000000000000000"
        private const val UNLOCK_PROOF_SALT = "80c55d2d1f1432551b82f5304b60e1aced87f823a6ed309d"
        private const val UNLOCK_API_URL = "https://api.mangatek.com/api/reader/unlock"
        private const val DEVICE_STRING = "Android | 1080x1920x24 | UTC | cores:8 | mem:undefined | touch:1 | dpr:2.75"
    }
}
