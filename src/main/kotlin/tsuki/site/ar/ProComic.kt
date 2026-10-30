package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.bitmap.Rect
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

import tsuki.util.NextJsData
import tsuki.util.generateUid
import tsuki.util.getTurnstileToken
import tsuki.util.parseJson
import tsuki.util.parseRaw
import tsuki.util.urlEncoded

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

import tsuki.Broken
@Broken("chapter images doenst work")
@MangaSourceParser("PROCOMIC", "ProComic", "ar")
internal class ProComic(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.PROCOMIC, 50) {

    override val configKeyDomain = ConfigKey.Domain("procomic.pro", "procomic.net")

    private val baseUrl get() = "https://$domain"
    private val siteHost get() = domain

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Cookie", "safe_browsing=off")
        .build()

    private fun htmlHeaders() = getRequestHeaders().newBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        return if (filter.query.isNullOrBlank()) {
            fetchListing(page) {
                addPathSegments("api/public/content")
                addQueryParameter("sort", sortParam(order))
                contentTypes(filter.types)?.let { addQueryParameter("type", it) }
                mangaStates(filter.states)?.let { addQueryParameter("progress", it) }
            }
        } else {
            fetchListing(page) {
                addPathSegments("api/public/series/search")
                addQueryParameter("search", filter.query!!.trim())
            }
        }
    }

    private suspend fun fetchListing(
        startPage: Int,
        url: HttpUrl.Builder.() -> Unit,
    ): List<Manga> {
        var currentPage = startPage
        var guard = 0
        while (guard++ < 20) {
            val fullUrl = baseUrl.toHttpUrl().newBuilder().apply(url)
                .addQueryParameter("limit", "50")
                .addQueryParameter("page", currentPage.toString())
                .build()
            val raw = webClient.httpGet(fullUrl, getRequestHeaders()).parseRaw()
            val json = JSONObject(raw)
            currentPage++
            val data = json.optJSONArray("data") ?: JSONArray()
            val meta = json.optJSONObject("meta")
            val pageNow = meta?.optInt("page", 1) ?: 1
            val totalPages = meta?.optInt("pages", 1) ?: 1
            val hasNext = pageNow < totalPages

            val mangas = (0 until data.length()).mapNotNull { i ->
                val obj = data.optJSONObject(i) ?: return@mapNotNull null
                val type = obj.optString("type")
                if (type == "novel") return@mapNotNull null
                if (obj.optBoolean("isBlockedSeries", false)) return@mapNotNull null
                parseManga(obj)
            }
            if (mangas.isNotEmpty()) return mangas
            if (!hasNext) return emptyList()
        }
        return emptyList()
    }

    private fun sortParam(order: SortOrder): String = when (order) {
        SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "popular"
        SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "latest_chapter"
        SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "latest"
        SortOrder.ALPHABETICAL -> "az"
        SortOrder.ALPHABETICAL_DESC -> "za"
        else -> "popular"
    }

    private fun contentTypes(types: Set<ContentType>): String? {
        if (types.isEmpty()) return null
        return when (types.first()) {
            ContentType.MANGA -> "manga"
            ContentType.MANHWA -> "manhwa"
            ContentType.MANHUA -> "manhua"
            else -> null
        }
    }

    private fun mangaStates(states: Set<MangaState>): String? {
        if (states.isEmpty()) return null
        return when (states.first()) {
            MangaState.ONGOING -> "مستمر"
            MangaState.FINISHED -> "مكتمل"
            MangaState.PAUSED -> "متوقف"
            else -> null
        }
    }

    private fun parseManga(obj: JSONObject): Manga? {
        val id = obj.optInt("id", -1)
        if (id <= 0) return null
        val title = obj.optString("title").ifBlank { return null }
        val slug = obj.optString("slug")
        val cdnPath = obj.optString("cdn_path")
        val thumbRaw = obj.optString("thumbnail").ifBlank { obj.optString("coverImage") }
        val coverUrl = if (thumbRaw.isBlank()) {
            null
        } else if (thumbRaw.startsWith("/")) {
            "https://$cdnPath.$siteHost$thumbRaw"
        } else {
            thumbRaw
        }
        return Manga(
            id = generateUid(id.toString()),
            url = id.toString(),
            publicUrl = "$baseUrl/ar/$slug-$id",
            title = title,
            coverUrl = coverUrl,
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
        val id = manga.url.toIntOrNull() ?: return manga
        val json = fetchContent(id) ?: return manga
        val slug = json.optString("slug")
        val type = json.optString("type", "manga")
        val chapters = fetchChapters(type, id.toString(), slug)
        return applyDetails(manga, json).copy(chapters = chapters)
    }

    private suspend fun fetchContent(id: Int): JSONObject? = runCatching {
        JSONObject(webClient.httpGet("$baseUrl/api/public/content/$id", getRequestHeaders()).parseRaw())
    }.getOrNull()

    private fun applyDetails(manga: Manga, json: JSONObject): Manga {
        val title = json.optString("title").ifBlank { manga.title }
        val cdnPath = json.optString("cdn_path")
        val thumbRaw = json.optString("thumbnail").ifBlank { json.optString("coverImage") }
        val coverUrl = if (thumbRaw.isBlank()) manga.coverUrl else if (thumbRaw.startsWith("/"))
            "https://$cdnPath.$siteHost$thumbRaw" else thumbRaw

        val metadata = json.optJSONObject("metadata")
        val author = metadata?.optStringOrList("author")
        val artist = metadata?.optStringOrList("artist")
        val origin = metadata?.optString("origin")
        val year = metadata?.optString("year")

        val altTitles = mutableSetOf<String>()
        metadata?.optJSONArray("altTitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(altTitles::add)
            }
        }

        val tags = linkedSetOf<String>()
        val type = json.optString("type")
        if (type.isNotBlank()) tags.add(type.replaceFirstChar { it.titlecase(Locale.ROOT) })
        origin?.takeIf { it.isNotBlank() }?.let { tags.add(it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }) }
        year?.takeIf { it.isNotBlank() }?.let(tags::add)
        metadata?.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(tags::add)
        }
        metadata?.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(tags::add)
        }

        val state = when (json.optString("progress").trim()) {
            "مستمر" -> MangaState.ONGOING
            "مكتمل" -> MangaState.FINISHED
            "متوقف" -> MangaState.PAUSED
            else -> null
        }

        val description = buildString {
            json.optString("description").takeIf { it.isNotBlank() }?.let {
                append(it.trim())
                if (altTitles.isNotEmpty()) append("\n\n")
            }
            if (altTitles.isNotEmpty()) {
                append("عناوين بديلة\n")
                altTitles.forEach { append("- ").append(it).append('\n') }
            }
        }.trim().ifBlank { null }

        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            authors = setOfNotNull(author, artist).filter { it.isNotBlank() }.toSet(),
            description = description,
            tags = tags.map { MangaTag(it, it, source) }.toSet(),
            state = state,
        )
    }

    private fun JSONObject.optStringOrList(key: String): String? {
        val raw = opt(key) ?: return null
        return when (raw) {
            is String -> raw.takeIf { it.isNotBlank() }
            is JSONArray -> (0 until raw.length())
                .mapNotNull { raw.optString(it).takeIf { s -> s.isNotBlank() } }
                .joinToString(", ").ifBlank { null }
            else -> null
        }
    }

    private suspend fun fetchChapters(type: String, mangaId: String, slug: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        while (page <= 100) {
            val url = "$baseUrl/api/public/$type/$mangaId/chapters?limit=100&order=desc&page=$page"
            val json = runCatching {
                JSONObject(webClient.httpGet(url, getRequestHeaders()).parseRaw())
            }.getOrNull() ?: break
            val data = json.optJSONArray("data") ?: break
            val total = json.optInt("total", data.length())
            for (i in 0 until data.length()) {
                val ch = data.optJSONObject(i) ?: continue
                if (ch.optString("language") != "AR") continue
                if (isLocked(ch)) continue
                chapters.add(parseChapter(ch, slug, mangaId))
            }
            if (chapters.size >= total) break
            if (data.length() == 0) break
            page++
        }
        return chapters.sortedBy { it.number }
    }

    private fun isLocked(ch: JSONObject): Boolean = listOf(
        "lockedForever",
        "lockedByCoins",
        "lockedByShortlink",
        "lockedByExclusive",
    ).any { ch.optBoolean(it, false) }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseChapter(ch: JSONObject, slug: String, mangaId: String): MangaChapter {
        val id = ch.optInt("id", -1)
        val number = ch.optString("chapter_number")
        val title = ch.optString("title").takeIf { it.isNotBlank() && it != number }
        val publishedAt = ch.optString("published_at")
        val uploadDate = runCatching {
            dateFormat.parse(publishedAt.take(19))?.time ?: 0L
        }.getOrDefault(0L)

        val displayName = buildString {
            append("الفصل ").append(number)
            title?.let { append(" - ").append(it) }
        }

        return MangaChapter(
            id = generateUid("$mangaId/$id"),
            title = displayName,
            number = number.toFloatOrNull() ?: 0f,
            volume = 0,
            url = "$mangaId/$id/$slug/$number",
            uploadDate = uploadDate,
            source = source,
            scanlator = ch.optString("translator").takeIf { it.isNotBlank() },
            branch = null,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/")
        if (parts.size < 4) return emptyList()

        val chapterId = parts[1].toIntOrNull() ?: return emptyList()
        val slug = parts[2]
        val number = parts[3]
        val chapterUrl = "https://$domain/ar/chapter/$slug-$number-$chapterId"

        val html = runCatching {
            webClient.httpGet(chapterUrl, htmlHeaders()).parseRaw()
        }.getOrNull() ?: return emptyList()

        val reader = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("chapterId") && node.has("appImages")
        } as? JSONObject ?: return emptyList()

        val readerChapterId = reader.optInt("chapterId", chapterId)
        val cdnPath = reader.optString("cdnPath")

        val images = mutableListOf<String>()
        reader.optJSONArray("appImages")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { obj ->
                    val url = obj.optString("mobile").ifBlank { obj.optString("desktop") }
                    if (url.isNotBlank()) images.add(url)
                }
            }
        }

        var pass: String?
        val deferred = reader.optJSONObject("deferredMedia")
        if (deferred != null) {
            val token = deferred.optString("token")
            val requireTurnstile = deferred.optBoolean("requireTurnstile")
            val turnstileMode = deferred.optString("turnstileMode")

            val headersBuilder = getRequestHeaders().newBuilder()
            if (requireTurnstile) {
                // Only fetch the login widget's key when the site explicitly asks for "visible".
                val loginKey = if (turnstileMode == "visible") loginTurnstileKey() else null
                val siteKey = loginKey ?: TURNSTILE_SITE_KEY
                val widget = if (loginKey != null) "login" else "reader"
                val turnstileToken = getTurnstileToken(context, chapterUrl, siteKey)
                headersBuilder["x-turnstile-token"] = turnstileToken
                headersBuilder["x-turnstile-widget"] = widget
            }

            val deferredUrl = "$baseUrl/chapter-deferred-media/$readerChapterId?token=${token.urlEncoded()}"
            val response = runCatching {
                webClient.httpGet(deferredUrl, headersBuilder.build()).parseJson()
            }.getOrNull()

            response?.optJSONObject("data")?.let { data ->
                pass = data.optString("pass").takeIf { it.isNotBlank() }

                data.optJSONArray("images")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let {
                            images.add(signedProxyUrl(it, pass))
                        }
                    }
                }
                data.optJSONArray("maps")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val entry = arr.optJSONObject(i) ?: continue
                        val plan = JSONObject().apply {
                            put("chapterId", readerChapterId)
                            put("token", entry.optString("token"))
                            put("method", entry.optString("method"))
                            put("cdnPath", cdnPath)
                            pass?.let { put("pass", it) }
                        }
                        images.add("http://$PIECE_HOST#${plan}")
                    }
                }
            }
        }

        return images.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun loginTurnstileKey(): String? = runCatching {
        webClient.httpGet("$baseUrl/api/auth/public-settings", getRequestHeaders())
            .parseJson()
            ?.optJSONObject("loginTurnstile")
            ?.takeIf { it.optBoolean("enabled", false) }
            ?.optString("siteKey")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun signedProxyUrl(image: String, pass: String?): String = buildString {
        append("http://$SIGNED_IMAGE_HOST?url=").append(image.urlEncoded())
        pass?.takeIf { it.isNotBlank() }?.let {
            append("&pass=").append(it.urlEncoded())
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return when (request.url.host) {
            PIECE_HOST -> runCatching { stitch(request, chain) }
                .getOrElse { errorResponse(request, it) }
            SIGNED_IMAGE_HOST -> runCatching { signAndRedirect(request, chain) }
                .getOrElse { errorResponse(request, it) }
            else -> chain.proceed(request)
        }
    }

    private fun signAndRedirect(request: Request, chain: Interceptor.Chain): Response {
        val url = request.url
        val image = url.queryParameter("url") ?: return chain.proceed(request)
        val pass = url.queryParameter("pass")

        val signed = runCatching { signImage(image, pass) }
            .getOrNull() ?: return chain.proceed(request)

        val signedUrl = "$baseUrl/api/cdn-image".toHttpUrl().newBuilder()
            .addQueryParameter("url", image)
            .addQueryParameter("token", signed.first)
            .addQueryParameter("expires", signed.second.toString())
            .build()

        return chain.proceed(request.newBuilder().url(signedUrl).build())
    }

    private fun signImage(image: String, pass: String?): Pair<String, Long> {
        val body = JSONObject().put("url", image)
        val headers = getRequestHeaders().newBuilder().apply {
            pass?.takeIf { it.isNotBlank() }?.let { set("x-chapter-pass", it) }
        }.build()
        val req = Request.Builder()
            .url("$baseUrl/api/cdn-image/sign")
            .headers(headers)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        context.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sign failed ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val token = json.optString("token")
            if (token.isBlank()) throw IOException("No sign token")
            return token to json.optLong("expires", 0L)
        }
    }

    private fun stitch(request: Request, chain: Interceptor.Chain): Response {
        val plan = JSONObject(request.url.fragment ?: throw IOException("No plan"))
        val chapterId = plan.optInt("chapterId")
        if (chapterId <= 0) throw IOException("Bad chapterId")
        val pass = plan.optString("pass").takeIf { it.isNotBlank() }

        val planBody = JSONObject().apply {
            put("token", plan.optString("token"))
            put("method", plan.optString("method"))
            put("cdnPath", plan.optString("cdnPath"))
        }
        val planHeaders = getRequestHeaders().newBuilder().apply {
            pass?.let { set("x-chapter-pass", it) }
        }.build()
        val planReq = Request.Builder()
            .url("$baseUrl/chapter-map-proxy-plan/$chapterId")
            .headers(planHeaders)
            .post(planBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val planJson = context.httpClient.newCall(planReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Plan failed ${resp.code}")
            JSONObject(resp.body?.string() ?: "{}")
        }
        val map = planJson.optJSONObject("data")?.optJSONObject("map")
            ?: throw IOException("Map missing")
        val dim = map.getJSONArray("dim")
        val pieces = map.getJSONArray("pieces")
        val order = map.getJSONArray("order")
        val rects = map.getJSONArray("rects")

        val width = dim.getInt(0)
        val height = dim.getInt(1)
        if (width <= 0 || height <= 0) throw IOException("Bad dim ${width}x$height")

        val bitmap = context.createBitmap(width, height)

        for (i in 0 until rects.length()) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val rect = rects.getJSONObject(i)
            val pieceUrl = pieces.getString(order.getInt(i))
            val pieceReq = Request.Builder()
                .url(pieceUrl)
                .headers(getRequestHeaders())
                .build()
            context.httpClient.newCall(pieceReq).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Piece failed ${resp.code}")
                context.redrawImageResponse(resp) { pieceBmp ->
                    bitmap.drawBitmap(
                        pieceBmp,
                        Rect(0, 0, pieceBmp.width, pieceBmp.height),
                        Rect(
                            rect.getInt("left"),
                            rect.getInt("top"),
                            rect.getInt("left") + rect.getInt("width"),
                            rect.getInt("top") + rect.getInt("height"),
                        ),
                    )
                    pieceBmp
                }.close()
            }
        }

        val basePieceUrl = pieces.getString(order.getInt(0))
        val baseReq = Request.Builder()
            .url(basePieceUrl)
            .headers(getRequestHeaders())
            .build()
        return context.httpClient.newCall(baseReq).execute().use { baseResp ->
            context.redrawImageResponse(baseResp) { _ -> bitmap }
        }
    }

    private fun errorResponse(request: Request, cause: Throwable): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(502)
            .message(cause.message ?: "procomic proxy failed")
            .body("".toResponseBody(null))
            .build()

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    companion object {
        private const val PIECE_HOST = "procomic.pieces"
        private const val SIGNED_IMAGE_HOST = "procomic.signed"
        private const val TURNSTILE_SITE_KEY = "0x4AAAAAACk1d7qWJi_of2qU"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
