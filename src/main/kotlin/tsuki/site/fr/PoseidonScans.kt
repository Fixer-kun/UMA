package tsuki.site.fr

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

import tsuki.util.NextJsData
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseRaw

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("POSEIDONSCANS", "Poseidon Scans", "fr")
internal class PoseidonScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.POSEIDONSCANS, 20) {

    override val configKeyDomain = ConfigKey.Domain("poseidon-scans.net")
    private val baseUrl get() = "https://$domain"

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != domain) return chain.proceed(request)

        val isImage = url.encodedPath.let { p ->
            p.endsWith(".webp") || p.endsWith(".jpg") || p.endsWith(".jpeg") ||
                    p.endsWith(".png") || p.endsWith(".avif")
        }

        val builder = request.newBuilder()
            .header("Referer", "$baseUrl/")
            .header("Origin", baseUrl)
        if (isImage) {
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        }
        return chain.proceed(builder.build())
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
    )

    @Volatile
    private var genresCache: Set<MangaTag>? = null
    private val genresMutex = Mutex()

    private fun fetchGenres(): Set<MangaTag> = GENRES
        .filter { it.isNotBlank() }
        .map { MangaTag(it, it, source) }
        .toSet()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = genresMutex.withLock {
            genresCache ?: fetchGenres().also { genresCache = it }
        }
        return MangaListFilterOptions(
            availableTags = genres,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> = scrapeSeries(page, order, filter)

    private suspend fun scrapeSeries(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            val sortBy = when (order) {
                SortOrder.UPDATED -> "latest_chapter"
                SortOrder.POPULARITY -> "popular"
                SortOrder.NEWEST -> "recent"
                SortOrder.ALPHABETICAL -> "alpha"
                SortOrder.RELEVANCE -> "most_chapters"
                else -> ""
            }
            addQueryParameter("sortBy", sortBy)

            addQueryParameter("viewMode", "list")
            addQueryParameter("minChapters", "0")
            addQueryParameter("maxChapters", "500")

            filter.query?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("search", it.trim())
            }
            
            val tagValues = mutableListOf<String>()
            filter.types.forEach { type ->
                when (type) {
                    ContentType.MANGA -> tagValues.add("MANGA")
                    ContentType.MANHWA -> tagValues.add("MANHWA")
                    ContentType.MANHUA -> tagValues.add("MANHUA")
                    else -> Unit
                }
            }
            filter.tags.forEach { tag -> tagValues.add(tag.title) }
            if (tagValues.isNotEmpty()) {
                addQueryParameter("tags", tagValues.joinToString(","))
            }

            filter.states.mapNotNull { state ->
                when (state) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    MangaState.PAUSED -> "hiatus"
                    MangaState.ABANDONED -> "cancelled"
                    else -> null
                }
            }.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("status", it.joinToString(","))
            }

            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        val doc = runCatching {
            webClient.httpGet(url, getRequestHeaders()).parseHtml()
        }.getOrNull() ?: return emptyList()

        return doc.select("div.grid a.block.group").mapNotNull { element ->
            val href = element.attr("href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = element.selectFirst("h2")?.text()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val coverPath = element.selectFirst("img[alt]")?.attr("srcset")
                ?.substringBefore(" ")
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.substringAfter("url=", "")
                ?.substringBefore("&", "")
                ?.takeIf { it.isNotBlank() }

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = "$baseUrl$href",
                title = title,
                coverUrl = coverPath?.let(::toApiCoverUrl),
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

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val slug = manga.url.substringAfterLast("/")
        if (slug.isBlank()) return@coroutineScope manga

        val detailsDeferred = async { fetchDetails(slug, manga) }
        val chaptersDeferred = async { fetchChapters(slug) }

        detailsDeferred.await().copy(chapters = chaptersDeferred.await())
    }

    private suspend fun fetchDetails(slug: String, fallback: Manga): Manga {
        val html = runCatching {
            webClient.httpGet("$baseUrl/serie/$slug", getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return fallback

        val data = NextJsData.findObject(html, "slug", slug, "title", "chapters")
            ?: return fallback

        return parseDetails(data, fallback)
    }

    private fun parseDetails(data: JSONObject, fallback: Manga): Manga {
        val slug = data.optString("slug").ifBlank { fallback.url.substringAfterLast("/") }
        val title = data.optString("title").ifBlank { fallback.title }
        val author = data.optString("author").takeIf { it.isNotBlank() }
        val artist = data.optString("artist").takeIf { it.isNotBlank() }

        val tags = mutableSetOf<MangaTag>()
        data.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        val display = it.replaceFirstChar { c -> c.titlecase(Locale.FRENCH) }
                        tags.add(MangaTag(display, display, source))
                    }
            }
        }

        val state = when (data.optString("status").trim().lowercase(Locale.FRENCH)) {
            "en cours" -> MangaState.ONGOING
            "terminé" -> MangaState.FINISHED
            "en pause", "hiatus" -> MangaState.PAUSED
            "annulé", "abandonné" -> MangaState.ABANDONED
            else -> null
        }

        return fallback.copy(
            url = "/serie/$slug",
            publicUrl = "$baseUrl/serie/$slug",
            title = title,
            coverUrl = "$baseUrl/api/covers/$slug.webp",
            authors = setOfNotNull(author, artist).filter { it.isNotBlank() }.toSet(),
            tags = tags,
            state = state,
            description = data.optString("description").trim().takeIf { it.isNotBlank() },
        )
    }

    private suspend fun fetchChapters(slug: String): List<MangaChapter> {
        val url = "$baseUrl/serie/$slug"
        runCatching {
            val html = webClient.httpGet(url, getRequestHeaders()).parseRaw()
            parseChaptersFromHtml(html, slug)
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        val retryUrl = "$baseUrl/serie/$slug?_=${System.currentTimeMillis()}"
        val retryHtml = runCatching {
            webClient.httpGet(retryUrl, getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return emptyList()
        return parseChaptersFromHtml(retryHtml, slug)
    }

    private fun parseChaptersFromHtml(html: String, slug: String): List<MangaChapter> {
        val data = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("manga") &&
                    node.optJSONObject("manga")?.has("chapters") == true
        } as? JSONObject ?: return emptyList()

        val mangaObj = data.optJSONObject("manga") ?: return emptyList()
        val isPremiumUser = data.optBoolean("isPremiumUser", false)
        val chaptersArray = mangaObj.optJSONArray("chapters") ?: return emptyList()
        val mangaSlug = mangaObj.optString("slug").ifBlank { slug }

        val now = System.currentTimeMillis()
        val result = mutableListOf<MangaChapter>()

        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.optJSONObject(i) ?: continue
            val number = ch.optDouble("number", Double.NaN).toFloat()
            if (number.isNaN()) continue

            val isPremium = ch.optBoolean("isPremium", false)
            val isLocked = isPremium && !isPremiumUser

            if (isLocked) {
                val premiumUntil = parseTimestamp(ch.optString("premiumUntil"))
                if (premiumUntil > 0 && now <= premiumUntil) continue
            }

            val numberString = if (number == number.toLong().toFloat()) {
                number.toLong().toString()
            } else {
                number.toString()
            }
            val isVolume = ch.optBoolean("isVolume", false) ||
                    (number % 1f == 0f &&
                            ch.optString("title").contains("volume", ignoreCase = true))

            val baseName = if (isVolume) "Volume $numberString" else "Chapitre $numberString"
            val rawTitle = ch.optString("title").takeIf { it.isNotBlank() }

            val displayName = buildString {
                if (isLocked) append("🔒 ")
                append(baseName)
                if (rawTitle != null) append(" - ").append(rawTitle)
                if (isLocked) {
                    val premiumUntil = parseTimestamp(ch.optString("premiumUntil"))
                    if (premiumUntil > 0) {
                        val parts = formatTimestamp(premiumUntil).split(" ")
                        append(" - Free the ")
                        append(parts.take(2).joinToString(" "))
                        append(" at ")
                        append(parts.getOrNull(2) ?: "")
                    }
                }
            }.trim()

            result.add(
                MangaChapter(
                    id = generateUid("$mangaSlug/$numberString"),
                    url = "/serie/$mangaSlug/chapter/$numberString",
                    title = displayName,
                    number = number,
                    volume = 0,
                    uploadDate = parseTimestamp(ch.optString("createdAt")),
                    scanlator = null,
                    branch = null,
                    source = source,
                ),
            )
        }

        return result.sortedBy { it.number }
    }

    private fun parseTimestamp(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(str).toEpochMilli() }.getOrDefault(0L)
    }

    private fun formatTimestamp(timestamp: Long): String =
        DateTimeFormatter.ofPattern("dd MMMM HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val html = runCatching {
            webClient.httpGet("$baseUrl${chapter.url}", getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return emptyList()

        val data = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("currentChapter") && node.has("initialData")
        } as? JSONObject ?: return emptyList()

        val currentChapter = data.optJSONObject("currentChapter") ?: return emptyList()
        if (currentChapter.optBoolean("isPremium", false)) {
            val sessionStatus = data.optString("sessionStatus")
            if (sessionStatus == "unauthenticated") {
                throw Exception(
                    "This chapter is premium. Please open Poseidon Scans in WebView to log in.",
                )
            }
            if (!data.optBoolean("isPremiumUser", false)) {
                throw Exception("This chapter is premium. You are not a premium user.")
            }
        }

        val images = data.optJSONObject("initialData")
            ?.optJSONArray("images") ?: return emptyList()

        val pages = mutableListOf<Pair<Int, String>>()
        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            val original = img.optString("originalUrl").takeIf { it.isNotBlank() } ?: continue
            val order = img.optInt("order", i)
            val absUrl = if (original.startsWith("http")) original else "$baseUrl$original"
            pages.add(order to absUrl)
        }

        return pages.sortedBy { it.first }.map { (_, url) ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun toApiCoverUrl(path: String): String {
        if (path.startsWith("http")) return path
        if (path.contains("storage/covers/")) {
            return "$baseUrl/api/covers/${path.substringAfter("storage/covers/")}"
        }
        if (path.startsWith("/api/covers/")) return baseUrl + path
        if (path.startsWith("/")) return baseUrl + path
        return "$baseUrl/api/covers/$path"
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    companion object {
        private val GENRES = listOf(
            "Académie",
            "Action",
            "Arts Martiaux",
            "Ascension de tour",
            "Aventure",
            "Chasseurs",
            "Comédie",
            "Cultivation",
            "Donjons",
            "Drame",
            "Fantaisie",
            "Harem",
            "Historique",
            "Horreur",
            "Isekai",
            "Magie",
            "Mature",
            "Murim",
            "Mystère",
            "Noblesse",
            "Post-apocalyptique",
            "Protagoniste surpuissant",
            "Psychologique",
            "Régression",
            "Réincarnation",
            "Romance",
            "Science-Fiction",
            "Sport",
            "Surnaturel",
            "Survie",
            "Système",
            "Thriller",
            "Tranche de vie",
            "Transmigration",
            "Vengeance",
            "Vie Scolaire",
        )
    }
}
