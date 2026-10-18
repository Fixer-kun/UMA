package tsuki.site.fr

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.NotFoundException
import tsuki.exception.ParseException

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

import tsuki.util.LinkResolver
import tsuki.util.NextJsData
import tsuki.util.generateUid
import tsuki.util.nl2br
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseJson
import tsuki.util.runCatchingCancellable
import tsuki.util.toTitleCase
import tsuki.util.urlBuilder
import tsuki.util.json.getBooleanOrDefault
import tsuki.util.json.getFloatOrDefault
import tsuki.util.json.getIntOrDefault
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSON
import tsuki.util.json.mapJSONNotNull

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("RAIJINSCANS", "Aniverse", "fr")
internal class Aniverse(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.RAIJINSCANS, pageSize = PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("aniverse.fr")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    private val genres: Set<MangaTag> = GENRES.mapTo(LinkedHashSet()) { (key, title) ->
        MangaTag(title = title, key = key, source = source)
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
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
            ContentType.ONE_SHOT,
        ),
    )
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val isCatalogue = order == SortOrder.UPDATED && filter.query.isNullOrBlank() &&
                filter.tags.size <= 1 && filter.states.isEmpty() && filter.types.isEmpty()
        if (isCatalogue) {
            val url = urlBuilder()
                .addPathSegments("api/manga")
                .addQueryParameter("page", page.toString())
            filter.tags.firstOrNull()?.let { url.addQueryParameter("genre", it.key) }
            return webClient.httpGet(url.build()).parseJson()
                .getJSONArray("items")
                .mapJSON { it.toManga() }
        }
        val url = urlBuilder()
            .addPathSegments("api/anime/search")
            .addQueryParameter("media", "manga")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", PAGE_SIZE.toString())
        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("q", it) }
        when (order) {
            SortOrder.POPULARITY -> "popularity"
            SortOrder.RATING -> "rating"
            SortOrder.NEWEST -> "newest"
            SortOrder.ALPHABETICAL -> "title"
            else -> null
        }?.let { url.addQueryParameter("sort", it) }
        filter.tags.forEach { url.addQueryParameter("genres", it.key) }
        filter.states.forEach { state ->
            when (state) {
                MangaState.ONGOING -> "Ongoing"
                MangaState.FINISHED -> "Finished"
                MangaState.PAUSED -> "On hiatus"
                MangaState.ABANDONED -> "Cancelled"
                else -> null
            }?.let { url.addQueryParameter("status", it) }
        }
        if (ContentType.ONE_SHOT in filter.types) url.addQueryParameter("type", "One-shot")
        val kind = filter.types.filterTo(EnumSet.noneOf(ContentType::class.java)) { it != ContentType.ONE_SHOT }
            .oneOrThrowIfMany()
        when (kind) {
            ContentType.MANGA -> "manga"
            ContentType.MANHWA -> "manhwa"
            ContentType.MANHUA -> "manhua"
            else -> null
        }?.let { url.addQueryParameter("kind", it) }
        return webClient.httpGet(url.build()).parseJson()
            .getJSONArray("data")
            .mapJSON { it.toManga() }
    }

    private fun JSONObject.toManga(): Manga {
        val slug = getString("slug")
        val url = "/manga/$slug/"
        val title = getString("title")
        val rating = getFloatOrDefault("rating", 0f)
        return Manga(
            id = generateUid(url),
            title = title,
            altTitles = setOfNotNull(
                getStringOrNull("titleEnglish"),
                getStringOrNull("titleRomaji"),
                getStringOrNull("titleNative"),
            ).filterNotTo(LinkedHashSet()) { it.equals(title, ignoreCase = true) },
            url = url,
            publicUrl = "https://$domain/manga/$slug",
            rating = if (rating > 0f) (rating / 10f).coerceAtMost(1f) else RATING_UNKNOWN,
            contentRating = null,
            coverUrl = getStringOrNull("image"),
            tags = emptySet(),
            state = parseState(getStringOrNull("status")),
            authors = emptySet(),
            source = source,
        )
    }
    
    override suspend fun getDetails(manga: Manga): Manga {
        val (slug, html) = loadMangaPage(manga)
        val pageUrl = "https://$domain/manga/$slug"

        val obj = NextJsData.findObject(
            html = html,
            key = "slug",
            value = slug,
            "databaseId",
            "genres",
        ) ?: throw ParseException("Manga data not found", pageUrl)

        val chapters = NextJsData.findArray(html) { first ->
            first.has("id") && first.has("number")
        }?.takeIf { it.length() > 0 }
            ?: fetchChaptersFromReader(slug, obj.getString("databaseId"))

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = System.currentTimeMillis()
        val title = obj.titleAsString() ?: manga.title
        val score = obj.getFloatOrDefault("averageScore", 0f)
        return manga.copy(
            title = title,
            publicUrl = pageUrl,
            altTitles = obj.altTitles(title).ifEmpty { manga.altTitles },
            description = obj.descriptionOrNull(),
            coverUrl = obj.optJSONObject("coverImage")?.getStringOrNull("large") ?: manga.coverUrl,
            rating = if (score > 0f) (score / 100f).coerceAtMost(1f) else manga.rating,
            contentRating = if (obj.getBooleanOrDefault("isAdult", false)) ContentRating.ADULT else manga.contentRating,
            tags = obj.genreTags(),
            state = parseState(obj.getStringOrNull("status")) ?: manga.state,
            authors = obj.peopleOrEmpty(),
            chapters = chapters?.mapJSONNotNull { jo ->
                if (parseDate(dateFormat, jo.getStringOrNull("premiumUntil")) > now) null
                else jo.toChapter(slug, dateFormat)
            }.orEmpty(),
        )
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val segments = link.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] !in LINK_PREFIXES) return null
        val slug = segments[1]
        val url = "/manga/$slug/"
        return getDetails(
            Manga(
                id = generateUid(url), title = slug, altTitles = emptySet(),
                url = url, publicUrl = "https://$domain/manga/$slug",
                rating = RATING_UNKNOWN, contentRating = null, coverUrl = null,
                tags = emptySet(), state = null, authors = emptySet(), source = source,
            ),
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val segments = chapter.url.split('/').filter { it.isNotEmpty() }
        if (segments.size < 3) throw ParseException("Invalid chapter url", chapter.url)
        return fetchReader(segments[1], segments[2])
            .getJSONArray("pages")
            .mapJSON { jo ->
                val src = jo.getString("src")
                MangaPage(
                    id = generateUid(jo.getStringOrNull("id") ?: src),
                    url = src, preview = null, source = source,
                )
            }
    }
    
    private suspend fun fetchReader(slug: String, chapterId: String): JSONObject {
        val url = urlBuilder()
            .addPathSegments("api/manga/reader")
            .addQueryParameter("manga", slug)
            .addQueryParameter("chapter", chapterId)
            .build()
        return webClient.httpGet(url).parseJson()
    }

    private suspend fun fetchChaptersFromReader(slug: String, mangaId: String): JSONArray? =
        runCatchingCancellable {
            val cardUrl = urlBuilder()
                .addPathSegments("api/manga/card-info")
                .addQueryParameter("id", mangaId)
                .build()
            val readHref = webClient.httpGet(cardUrl).parseJson().getStringOrNull("readHref")
                ?: return@runCatchingCancellable null
            fetchReader(slug, readHref.trimEnd('/').substringAfterLast('/')).optJSONArray("chapters")
        }.getOrNull()

    private fun slugOf(url: String): String =
        url.substringAfter("/manga/").substringBefore('?').trim('/')

    private suspend fun loadMangaPage(manga: Manga): Pair<String, String> {
        val slug = slugOf(manga.url)
        return try {
            slug to webClient.httpGet("https://$domain/manga/$slug").body.string()
        } catch (e: NotFoundException) {
            val renamed = findRenamedSlug(manga) ?: throw e
            renamed to webClient.httpGet("https://$domain/manga/$renamed").body.string()
        }
    }

    private suspend fun findRenamedSlug(manga: Manga): String? {
        val titles = (manga.altTitles + manga.title).mapNotNullTo(HashSet()) {
            it.normalizedTitle().ifEmpty { null }
        }
        val matches = getListPage(1, SortOrder.RELEVANCE, MangaListFilter(query = manga.title)).filter {
            it.title.normalizedTitle() in titles
        }
        return matches.singleOrNull()?.let { slugOf(it.url) }?.takeUnless { it == slugOf(manga.url) }
    }

    private fun String.normalizedTitle(): String =
        lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun JSONObject.toChapter(slug: String, dateFormat: DateFormat): MangaChapter {
        val url = "/read/$slug/${getString("id")}"
        val number = getFloatOrDefault("number", 0f)
        val isVolume = getStringOrNull("kind") == "tome"
        return MangaChapter(
            id = generateUid(url),
            title = getStringOrNull("title")?.replaceFirst(CHAPTER_PREFIX_REGEX, "")?.trim()?.ifEmpty { null }
                ?: if (isVolume) "Tome ${formatNumber(number)}" else null,
            number = number,
            volume = if (isVolume) number.toInt() else getIntOrDefault("volume", 0),
            url = url,
            scanlator = null,
            uploadDate = parseDate(dateFormat, getStringOrNull("updatedAt")),
            branch = null,
            source = source,
        )
    }

    private fun JSONObject.titleAsString(): String? {
        val t = optJSONObject("title") ?: return getStringOrNull("title")
        return t.getStringOrNull("userPreferred")
            ?: t.getStringOrNull("english")
            ?: t.getStringOrNull("romaji")
            ?: t.getStringOrNull("native")
    }

    private fun JSONObject.altTitles(title: String): Set<String> {
        val t = optJSONObject("title") ?: return emptySet()
        return setOfNotNull(
            t.getStringOrNull("english"),
            t.getStringOrNull("romaji"),
            t.getStringOrNull("native"),
        ).filterNotTo(LinkedHashSet()) { it.equals(title, ignoreCase = true) }
    }

    private fun JSONObject.descriptionOrNull(): String? {
        getStringOrNull("description")?.let { return it }
        val sources = optJSONObject("savedMetadata")
            ?.optJSONObject("otherInfo")
            ?.optJSONObject("sources")
            ?: return null
        for (key in sources.keys()) {
            sources.optJSONObject(key)?.getStringOrNull("description")?.let { return it.trim().nl2br() }
        }
        return null
    }

    private fun JSONObject.genreTags(): Set<MangaTag> {
        val arr = optJSONArray("genres") ?: return emptySet()
        val result = LinkedHashSet<MangaTag>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optString(i).trim()
            if (name.isEmpty()) continue
            result += genres.find {
                it.key.equals(name, ignoreCase = true) || it.title.equals(name, ignoreCase = true)
            } ?: MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
        }
        return result
    }

    private fun JSONObject.peopleOrEmpty(): Set<String> {
        val result = LinkedHashSet<String>()
        for (field in arrayOf("authors", "artists")) {
            val arr = optJSONArray(field) ?: continue
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { result += it }
            }
        }
        return result
    }

    private fun parseState(raw: String?): MangaState? =
        when (raw?.lowercase(Locale.ROOT)?.replace(" ", "")?.replace("_", "")) {
            "ongoing", "releasing" -> MangaState.ONGOING
            "finished" -> MangaState.FINISHED
            "onhiatus", "hiatus" -> MangaState.PAUSED
            "cancelled", "canceled" -> MangaState.ABANDONED
            "upcoming", "notyetreleased" -> MangaState.UPCOMING
            else -> null
        }

    private fun parseDate(dateFormat: DateFormat, raw: String?): Long {
        if (raw.isNullOrEmpty()) return 0L
        return try { dateFormat.parse(raw)?.time ?: 0L } catch (_: Exception) { 0L }
    }

    private fun formatNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    companion object {
        private const val PAGE_SIZE = 28
        private val LINK_PREFIXES = setOf("manga", "read")
        private val CHAPTER_PREFIX_REGEX = Regex(
            """^\s*(?:chapitre|chapter)\s*\d+(?:[.,]\d+)?\s*(?:[-–—:.]\s*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val GENRES = listOf(
            "Action" to "Action",
            "Adventure" to "Aventure",
            "Comedy" to "Comédie",
            "Drama" to "Drame",
            "Ecchi" to "Ecchi",
            "Fantasy" to "Fantasy",
            "Horror" to "Horreur",
            "Mahou Shoujo" to "Mahou shoujo",
            "Mecha" to "Mecha",
            "Music" to "Musique",
            "Mystery" to "Mystère",
            "Psychological" to "Psychologique",
            "Romance" to "Romance",
            "Sci-Fi" to "Science-fiction",
            "Slice of Life" to "Tranche de vie",
            "Sports" to "Sport",
            "Supernatural" to "Surnaturel",
            "Thriller" to "Thriller",
        )
    }
}
