package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.network.OkHttpWebClient

import tsuki.model.ContentType
import tsuki.model.Favicons
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

import tsuki.util.attrAsAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.await
import tsuki.util.generateUid
import tsuki.util.mapNotNullToSet
import tsuki.util.parseHtml
import tsuki.util.runCatchingCancellable
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.milliseconds

@MangaSourceParser("BATCAVE", "Batcave", "en", ContentType.COMICS)
internal class Batcave(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.BATCAVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("batcave.biz")

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)

    @Volatile
    private var availableTags: Set<MangaTag>? = null
    private var filterFetchFailed = false

    private val rawHttpClient: OkHttpClient by lazy {
        context.httpClient.newBuilder()
            .addInterceptor(::refererInterceptor)
            .build()
    }

    private val apiClient: OkHttpWebClient by lazy {
        OkHttpWebClient(rawHttpClient, source)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
    )

    private fun refererInterceptor(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val referer = when {
            url.contains("readcomicsonline.ru") -> "https://readcomicsonline.ru/"
            else -> "https://$domain/"
        }
        return chain.proceed(request.newBuilder().header("Referer", referer).build())
    }

    private val dleGuardMutex = Mutex()

    @Volatile
    private var lastGuardSolveAt = 0L

    private companion object {
        const val DLE_GUARD_COOKIE = "__guard_trust"
        const val GUARD_TRUST_WINDOW_MS = 5_000L
        const val DLE_GUARD_MAX_NONCE = 1_000_000
        const val DLE_GUARD_MIN_WORK_MS = 50L
        val DLE_GUARD_TOKEN_REGEX = Regex("""\btoken:\s*"([^"]+)"""")
    }

    private suspend fun captureDocument(
        initialUrl: String,
        allowBrowserAction: Boolean = true,
    ): Document {
        tryHttpDocument(initialUrl)?.let { return it }

        if (solveDleGuard(initialUrl)) {
            tryHttpDocument(initialUrl)?.let { return it }
        }

        loadDocumentViaWebView(initialUrl)?.let { return it }

        if (allowBrowserAction) {
            context.requestBrowserAction(this, initialUrl)
        }
        throw ParseException("Failed to load page via webview", initialUrl)
    }

    private suspend fun tryHttpDocument(url: String): Document? {
        val response = runCatchingCancellable { apiClient.httpGet(url) }.getOrNull() ?: return null
        return response.use { res ->
            if (res.request.url.isDleGuard()) return null
            val doc = runCatchingCancellable { res.parseHtml() }.getOrNull() ?: return null

            if (hasValidBatCaveContent(doc)) return doc

            if (isActiveCloudflareChallenge(doc.outerHtml())) return null
            doc
        }
    }

    private suspend fun loadDocumentViaWebView(url: String): Document? {
        val script = """
        (() => {
            return new Promise(resolve => {
                const finish = () => {
                    resolve(document.documentElement ? document.documentElement.outerHTML : "");
                };
                if (document.readyState === "complete") {
                    setTimeout(finish, 200);
                } else {
                    window.addEventListener("load", () => setTimeout(finish, 200), { once: true });
                }
                setTimeout(finish, 3000);
            });
        })();
    """.trimIndent()

        val html = withTimeoutOrNull(10_000L.milliseconds) { context.evaluateJs(url, script) } ?: return null
        if (html.isBlank()) return null

        val doc = Jsoup.parse(html, url)
        if (hasValidBatCaveContent(doc)) return doc
        if (isDleGuardPage(doc)) return null
        if (isActiveCloudflareChallenge(html)) return null
        return doc
    }

    private suspend fun solveDleGuardInWebView(url: String): Boolean {
        val script = """
        (() => new Promise(resolve => {
            const started = Date.now();
            const check = () => {
                if (!location.pathname.startsWith("/_c") || document.cookie.indexOf("$DLE_GUARD_COOKIE=") >= 0) {
                    resolve("ok");
                } else if (Date.now() - started > 25000) {
                    resolve("timeout");
                } else {
                    setTimeout(check, 250);
                }
            };
            check();
        }))();
    """.trimIndent()
        runCatchingCancellable {
            withTimeoutOrNull(30_000L.milliseconds) { context.evaluateJs(url, script) }
        }
        return hasDleGuardTrust()
    }

    private suspend fun solveDleGuard(url: String): Boolean = dleGuardMutex.withLock {
        if (System.currentTimeMillis() - lastGuardSolveAt < GUARD_TRUST_WINDOW_MS) {
            return@withLock true
        }
        val solved = runCatchingCancellable { solveDleGuardChallenge(url) }.getOrDefault(false) ||
                solveDleGuardInWebView(url)
        if (solved) {
            lastGuardSolveAt = System.currentTimeMillis()
        }
        solved
    }

    private suspend fun solveDleGuardChallenge(url: String): Boolean {
        val request = Request.Builder()
            .get()
            .url(url)
            .build()
        val token = rawHttpClient.newCall(request).await().use { response ->
            if (!response.request.url.isDleGuard()) return true
            DLE_GUARD_TOKEN_REGEX.find(response.body?.string() ?: return false)?.groupValues?.get(1)
        } ?: return false
        val startedAt = System.currentTimeMillis()
        val digest = MessageDigest.getInstance("SHA-256")
        val nonce = (0 until DLE_GUARD_MAX_NONCE).firstOrNull { n ->
            digest.digest("$token:$n".toByteArray())[0] == 0.toByte()
        } ?: return false
        val hash = digest.digest("$token:$nonce".toByteArray()).joinToString("") { "%02x".format(it) }
        delay((maxOf(DLE_GUARD_MIN_WORK_MS, (nonce / 50 + 1) * 17L) - (System.currentTimeMillis() - startedAt)).milliseconds)
        val form = mapOf(
            "token" to token.urlEncoded(),
            "mode" to "modern",
            "workTime" to (System.currentTimeMillis() - startedAt).toString(),
            "iterations" to (nonce + 1).toString(),
            "hasCrypto" to "1",
            "pow_nonce" to nonce.toString(),
            "pow_hash" to hash,
            "webdriver" to "0",
            "touch" to "1",
            "screen_w" to "412",
            "screen_h" to "915",
            "screen_cd" to "24",
            "tz" to (-TimeZone.getDefault().getOffset(startedAt) / 60_000).toString(),
            "dpr" to "2.625",
            "cdp" to "0",
            "cdpf" to "",
        )
        val postRequest = Request.Builder()
            .url("https://$domain/_v".toHttpUrl())
            .post(
                form.entries.joinToString("&") { (k, v) ->
                    "${k.urlEncoded()}=${v.urlEncoded()}"
                }.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
            )
            .build()
        rawHttpClient.newCall(postRequest).await().close()
        return hasDleGuardTrust()
    }

    private fun hasDleGuardTrust(): Boolean {
        val cookies = rawHttpClient.cookieJar.loadForRequest("https://$domain/".toHttpUrl())
        return cookies.any { it.name == DLE_GUARD_COOKIE }
    }

    private fun HttpUrl.isDleGuard(): Boolean = pathSegments.firstOrNull() == "_c"

    private fun isDleGuardPage(doc: Document): Boolean =
        doc.location().toHttpUrlOrNull()?.isDleGuard() == true ||
                doc.selectFirst("script:containsData(pow_nonce)") != null

    private fun hasValidBatCaveContent(doc: Document): Boolean {
        if (isDleGuardPage(doc)) return false
        return doc.select("#dle-content > .readed, div.readed.d-flex.short").isNotEmpty() ||
                doc.select("script:containsData(__DATA__)").isNotEmpty() ||
                doc.select("script:containsData(__XFILTER__)").isNotEmpty() ||
                doc.select("h1.serie-title").isNotEmpty() ||
                doc.title().contains("BatCave", ignoreCase = true)
    }

    private fun isActiveCloudflareChallenge(html: String): Boolean {
        if (html.length < 100) return true
        val lower = html.lowercase()
        return (lower.contains("just a moment") && lower.contains("cloudflare")) ||
                (lower.contains("checking your browser") && lower.contains("cloudflare")) ||
                lower.contains("cf-browser-verification") ||
                lower.contains("cf-chl-opt")
    }

    override suspend fun getFavicons(): Favicons {
        if (!hasDleGuardTrust()) {
            solveDleGuard("https://$domain/")
        }
        return super.getFavicons()
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val pagePath = if (page > 1) "page/$page/" else ""
        val (sortBy, direction) = when (order) {
            SortOrder.POPULARITY -> "rating" to "desc"
            SortOrder.NEWEST -> "date" to "desc"
            SortOrder.ALPHABETICAL -> "title" to "asc"
            else -> "editdate" to "desc"
        }
        return when {
            !filter.query.isNullOrEmpty() -> {
                val url = "https://$domain".toHttpUrl().newBuilder()
                    .addPathSegment("search")
                    .addPathSegment(filter.query!!.trim())
                if (page > 1) {
                    url.addPathSegment("page").addPathSegment(page.toString())
                }
                parseReadedList(fetchDocument(url.addPathSegment("").build().toString()))
            }

            filter.tags.isNotEmpty() -> {
                val filterPath = buildString {
                    append("g=").append(filter.tags.joinToString(",") { it.key }).append('/')
                }
                val form = mapOf(
                    "dlenewssortby" to sortBy,
                    "dledirection" to direction,
                    "set_new_sort" to "dle_sort_xfilter",
                    "set_direction_sort" to "dle_direction_xfilter",
                )
                parseReadedList(fetchDocument("https://$domain/ComicList/$filterPath$pagePath", form))
            }

            order == SortOrder.UPDATED -> parseLatestList(fetchDocument("https://$domain/$pagePath"))

            else -> {
                val form = mapOf(
                    "dlenewssortby" to sortBy,
                    "dledirection" to direction,
                    "set_new_sort" to "dle_sort_cat_1",
                    "set_direction_sort" to "dle_direction_cat_1",
                )
                parseReadedList(fetchDocument("https://$domain/comix/$pagePath", form))
            }
        }
    }

    private suspend fun fetchDocument(url: String, form: Map<String, String>? = null): Document {
        var guardSolved = false
        while (true) {
            val result = runCatchingCancellable {
                if (form == null) apiClient.httpGet(url) else apiClient.httpPost(url.toHttpUrl(), form)
            }
            val response = result.getOrNull()
            val error = result.exceptionOrNull()
            val isGuarded = response?.request?.url?.isDleGuard() == true ||
                    (error as? HttpStatusException)?.url?.toHttpUrlOrNull()?.isDleGuard() == true
            if (!isGuarded) {
                if (error != null) throw error
                val doc = checkNotNull(response).parseHtml()
                if (isActiveCloudflareChallenge(doc.outerHtml())) {
                    context.requestBrowserAction(this, url)
                }
                return doc
            }
            response?.close()
            if (guardSolved || !solveDleGuard(if (form == null) url else "https://$domain/")) {
                context.requestBrowserAction(this, url)
            }
            guardSolved = true
        }
    }

    private fun parseLatestList(doc: Document): List<Manga> {
        return doc.select("#content-load > .latest.grid-item").mapNotNull { item ->
            val titleElement = item.selectFirst(".latest__title > a") ?: return@mapNotNull null
            val title = titleElement.ownText().trim().ifEmpty { return@mapNotNull null }
            val href = titleElement.attrAsRelativeUrl("href")
            val img = item.selectFirst(".latest__img img")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = titleElement.attrAsAbsoluteUrl("href"),
                title = title,
                altTitles = emptySet(),
                authors = emptySet(),
                description = null,
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                state = null,
                coverUrl = img?.attrAsAbsoluteUrl("src") ?: img?.attrAsAbsoluteUrl("data-src"),
                contentRating = null,
                source = source,
            )
        }
    }

    private fun parseReadedList(doc: Document): List<Manga> {
        val items = doc.select("#dle-content > .readed").ifEmpty { doc.select("div.readed.d-flex.short") }
        return items.mapNotNull { item ->
            val titleElement = item.selectFirst(".readed__title > a") ?: return@mapNotNull null
            val img = item.selectFirst(".readed__img img")
            val href = titleElement.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = titleElement.attrAsAbsoluteUrl("href"),
                title = titleElement.ownText().ifEmpty { titleElement.text() },
                altTitles = emptySet(),
                authors = emptySet(),
                description = null,
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                state = null,
                coverUrl = img?.attrAsAbsoluteUrl("data-src") ?: img?.attrAsAbsoluteUrl("src"),
                contentRating = null,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = captureDocument(manga.url.toAbsoluteUrl(domain))

        val scriptData = extractScriptJson(doc, "__DATA__")
            ?: throw ParseException("Script data not found", manga.url)

        val jsonData = JSONObject(scriptData)
        val newsId = jsonData.getLong("news_id")
        val chaptersJson = jsonData.getJSONArray("chapters")

        val chapters = List(chaptersJson.length()) { i ->
            val chapter = chaptersJson.getJSONObject(i)
            val chapterId = chapter.getLong("id")
            MangaChapter(
                id = generateUid("$newsId/$chapterId"),
                url = "/reader/$newsId/$chapterId",
                number = chapter.optDouble("posi", 0.0).toFloat(),
                title = chapter.optString("title").takeIf { it.isNotEmpty() },
                uploadDate = dateFormat.parseSafe(chapter.optString("date")),
                source = source,
                scanlator = null,
                branch = null,
                volume = 0,
            )
        }.reversed()

        val author = doc.selectFirst("li:contains(Publisher:)")
            ?.text()
            ?.substringAfter("Publisher:")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val state = when (
            doc.selectFirst("li:contains(Release type:)")?.text()?.substringAfter("Release type:")?.trim()
        ) {
            "Ongoing" -> MangaState.ONGOING
            else -> MangaState.FINISHED
        }

        val tagLinks = doc.getElementsByAttributeValueContaining("href", "/genres/")
        val tags = if (tagLinks.isNotEmpty()) {
            availableTags?.let { allTags ->
                tagLinks.mapNotNullToSet { a ->
                    val tagName = a.text()
                    allTags.find { it.title.equals(tagName, ignoreCase = true) }
                }
            }
        } else {
            null
        }

        return manga.copy(
            title = doc.selectFirst("header.page__header h1")?.text() ?: manga.title,
            coverUrl = manga.coverUrl?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("div.page__poster img")?.attrAsAbsoluteUrl("src"),
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
            description = doc.select("div.page__text.full-text.clearfix").text().takeIf { it.isNotEmpty() },
            tags = tags ?: manga.tags,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (newsId, rawId) = chapter.url.substringAfter("reader/").split("/", limit = 2)
        val id = Regex("^\\d+").find(rawId)?.value ?: rawId
        val jsonBody = JSONObject().apply {
            put("news_id", newsId)
            put("chapter_id", id)
        }.toString()

        val request = Request.Builder()
            .url("https://$domain/engine/ajax/controller.php?mod=api&action=reader/getChapterData")
            .header("Referer", "https://$domain/")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        val response = rawHttpClient.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: throw ParseException("Empty response", chapter.url))
        val data = json.getJSONObject("data")
        val images = data.optJSONArray("images") ?: throw ParseException("No images found", chapter.url)

        return (0 until images.length()).map { i ->
            var img = images.getString(i).trim()
            if (!img.startsWith("http")) img = "https://$domain$img"
            MangaPage(id = generateUid(img), url = img, preview = null, source = source)
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private suspend fun fetchTags(): Set<MangaTag> {
        availableTags?.let { return it }
        if (filterFetchFailed) return emptySet()
        try {
            val doc = captureDocument("https://$domain/comix/")
            val scriptData = doc.selectFirst("script:containsData(__XFILTER__)")?.data()
                ?: throw ParseException("Filter data not found", "https://$domain/comix")
            val genresJson = scriptData.substringAfter("\"g\":{").substringBefore("}}}") + "}"
            val genresObj = JSONObject("{$genresJson}")
            val valuesArray = genresObj.getJSONArray("values")

            val tags = buildSet {
                repeat(valuesArray.length()) { i ->
                    val genre = valuesArray.getJSONObject(i)
                    add(
                        MangaTag(
                            key = genre.getInt("id").toString(),
                            title = genre.getString("value").replaceFirstChar { it.uppercase() },
                            source = source,
                        )
                    )
                }
            }
            availableTags = tags
            return tags
        } catch (_: Exception) {
            filterFetchFailed = true
            return emptySet()
        }
    }

    @Suppress("SameParameterValue")
    private fun extractScriptJson(doc: Document, varName: String): String? {
        val assignment = Regex("""(?:window\.)?${Regex.escape(varName)}\s*=\s*(?=\{)""")
        for (script in doc.select("script")) {
            val data = script.data()
            for (match in assignment.findAll(data)) {
                extractBalancedObject(data, match.range.last + 1)?.let { return it }
            }
        }
        doc.selectFirst("script[type=application/json][id*=${varName.trim('_')}]")
            ?.data()?.trim()?.takeIf { it.startsWith("{") }
            ?.let { return it }
        return null
    }

    private fun extractBalancedObject(text: String, start: Int): String? {
        var depth = 0
        var quote = 0.toChar()
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quote != 0.toChar()) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> quote = 0.toChar()
                }
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun SimpleDateFormat.parseSafe(date: String?): Long =
        if (date.isNullOrBlank()) 0L else runCatching { parse(date)?.time ?: 0L }.getOrDefault(0L)
}
