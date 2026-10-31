package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentType
import tsuki.model.Favicon
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

import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseJson
import tsuki.util.json.getLongOrDefault
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSON
import tsuki.util.json.mapJSONToSet

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.util.EnumSet

@MangaSourceParser("WESTMANGA", "WestManga", "id")
internal class Westmanga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.WESTMANGA, 20) {

    override val configKeyDomain = ConfigKey.Domain("v1.westmanga.my")

    private val apiScheme: String get() = "https"
    private val apiDomain: String get() = domain

    private var genresCache: Set<MangaTag>? = null
    private val mutex = Mutex()

    private data class VisitToken(val token: String, val expiresAt: Long)
    @Volatile private var visitToken: VisitToken? = null
    private val visitTokenMutex = Mutex()
    private val visitTokenRefreshBufferMillis = 5 * 60_000L // 5 minutes, matching the site's logic

    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(Favicon("$apiScheme://$domain/favicon.ico", 48, null)),
            domain,
        )
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchGenres(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$apiScheme://$apiDomain/api/contents".toHttpUrl().newBuilder().apply {
            if (!filter.query.isNullOrEmpty()) {
                addQueryParameter("q", filter.query)
            }
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", pageSize.toString())
            addQueryParameter("type", "Comic")

            when (order) {
                SortOrder.UPDATED -> addQueryParameter("sort", "updated_at")
                SortOrder.POPULARITY -> addQueryParameter("sort", "views")
                SortOrder.ALPHABETICAL -> addQueryParameter("sort", "title")
                SortOrder.NEWEST -> addQueryParameter("sort", "created_at")
                else -> {}
            }

            filter.tags.forEach { tag ->
                addQueryParameter("genre[]", tag.key)
            }

            filter.states.oneOrThrowIfMany()?.let {
                addQueryParameter(
                    "status",
                    when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        else -> return@let
                    },
                )
            }

            filter.types.oneOrThrowIfMany()?.let {
                addQueryParameter(
                    "country",
                    when (it) {
                        ContentType.MANGA -> "JP"
                        ContentType.MANHWA -> "KR"
                        ContentType.MANHUA -> "CN"
                        else -> return@let
                    },
                )
            }
        }.build()

        val response = apiGet(url)
        val mangaArray = response.optJSONArray("data") ?: return emptyList()

        return mangaArray.mapJSON { jo ->
            val slug = jo.getString("slug")
            Manga(
                id = generateUid(slug),
                title = jo.getString("title"),
                altTitles = emptySet(),
                url = "/manga/$slug/",
                publicUrl = "$apiScheme://$domain/comic/$slug",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = absoluteUrl(jo.getString("cover")),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSurrounding("/manga/", "/")
        val url = "$apiScheme://$apiDomain/api/comic/$slug".toHttpUrl()

        val response = apiGet(url)
        val data = response.getJSONObject("data")

        val tags = buildSet {
            when (data.getStringOrNull("country_id") ?: data.getStringOrNull("country")) {
                "JP" -> add(MangaTag("Manga", "manga", source))
                "CN" -> add(MangaTag("Manhua", "manhua", source))
                "KR" -> add(MangaTag("Manhwa", "manhwa", source))
            }
            if (data.optBoolean("color", false)) {
                add(MangaTag("Colored", "colored", source))
            }
            data.optJSONArray("genres")?.let { genres ->
                for (i in 0 until genres.length()) {
                    val genre = genres.getJSONObject(i)
                    add(
                        MangaTag(
                            title = genre.getString("name"),
                            key = genre.getInt("id").toString(),
                            source = source,
                        ),
                    )
                }
            }
        }

        val description = buildString {
            data.getStringOrNull("sinopsis")?.let { synopsis ->
                append(Jsoup.parseBodyFragment(synopsis).wholeText().trim())
            }
            data.getStringOrNull("alternative_name")?.let { alt ->
                append("\n\nAlternative Name: ")
                append(alt.trim())
            }
        }

        val state = when (data.getStringOrNull("status")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        val chapters = data.optJSONArray("chapters")?.mapJSON { chapterObj ->
            val chapterSlug = chapterObj.getString("slug")
            val updatedAt = chapterObj.optJSONObject("updated_at")?.getLongOrDefault("time", 0L)
                ?: chapterObj.getLongOrDefault("updated_at", 0L)
            MangaChapter(
                id = generateUid(chapterSlug),
                url = "/$chapterSlug/",
                title = "Chapter ${chapterObj.getString("number")}",
                number = chapterObj.getString("number").toFloatOrNull() ?: 0f,
                volume = 0,
                branch = null,
                uploadDate = updatedAt * 1000,
                scanlator = null,
                source = source,
            )
        } ?: emptyList()

        return manga.copy(
            title = data.getString("title"),
            coverUrl = absoluteUrl(data.getString("cover")),
            tags = tags,
            authors = setOfNotNull(data.getStringOrNull("author")),
            description = description.takeIf { it.isNotEmpty() },
            state = state,
            chapters = chapters.reversed(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val slug = chapter.url.removeSurrounding("/", "/")
        val url = "$apiScheme://$apiDomain/api/v/$slug".toHttpUrl()

        val response = apiGet(url)
        val images = response.optJSONObject("data")?.getJSONArray("images")
            ?: response.getJSONArray("images")

        val pages = ArrayList<MangaPage>(images.length())
        for (i in 0 until images.length()) {
            val imageUrl = absoluteUrl(images.getString(i))
            pages.add(
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source,
                ),
            )
        }
        return pages
    }

    private suspend fun fetchGenres(): Set<MangaTag> = mutex.withLock {
        genresCache?.let { return@withLock it }

        val url = "$apiScheme://$apiDomain/api/contents/genres".toHttpUrl()
        val response = apiGet(url)
        val genresArray = response.optJSONArray("data") ?: return@withLock emptySet()

        val genres = genresArray.mapJSONToSet { genreObj ->
            MangaTag(
                title = genreObj.getString("name"),
                key = genreObj.getInt("id").toString(),
                source = source,
            )
        }

        genresCache = genres
        return@withLock genres
    }

    private suspend fun getVisitToken(forceRefresh: Boolean = false): String? = visitTokenMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = visitToken
        if (!forceRefresh && cached != null && cached.expiresAt - now > visitTokenRefreshBufferMillis) {
            return@withLock cached.token
        }
        val fresh = runCatching { fetchVisitToken() }.getOrNull()
        if (fresh != null) {
            visitToken = fresh
            return@withLock fresh.token
        }
        if (forceRefresh) visitToken = null
        visitToken?.token
    }

    private suspend fun fetchVisitToken(): VisitToken {
        val now = System.currentTimeMillis()
        val url = "$apiScheme://$apiDomain/api/visit-token".toHttpUrl()
        val response = webClient.httpGet(url, baseHeaders()).parseJson()

        val data = response.optJSONObject("data") ?: response
        val token = data.getString("token")
        val expiresAtRaw = data.getLongOrDefault("expires_at", 0L)
        val expiresAtMs = when {
            expiresAtRaw <= 0L -> now + 60 * 60_000L // fallback: 1 hour
            expiresAtRaw < 10_000_000_000L -> expiresAtRaw * 1000L // seconds
            else -> expiresAtRaw // milliseconds
        }
        return VisitToken(token, expiresAtMs)
    }

    private fun absoluteUrl(path: String): String =
        if (path.startsWith("http")) path else "$apiScheme://$domain${if (path.startsWith('/')) "" else "/"}$path"

    private fun baseHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Referer", "$apiScheme://$domain/")
        .add("Origin", "$apiScheme://$domain")
        .build()

    private suspend fun createApiHeaders(): Headers = baseHeaders().newBuilder().apply {
        getVisitToken()?.let { set("X-WM-Visit", it) }
    }.build()

    private suspend fun apiGet(url: HttpUrl): JSONObject = try {
        webClient.httpGet(url, createApiHeaders()).parseJson()
    } catch (e: HttpStatusException) {
        if (e.statusCode == 403 || e.statusCode == 404) {
            getVisitToken(forceRefresh = true)
            webClient.httpGet(url, createApiHeaders()).parseJson()
        } else {
            throw e
        }
    }
}