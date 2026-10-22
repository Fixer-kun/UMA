@file:OptIn(ExperimentalSerializationApi::class)

package tsuki.site.all

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.SinglePageMangaParser

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

import tsuki.util.parseAsProto
import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.Semaphore

@Serializable
data class MangaPlusResponse(
    @ProtoNumber(1) val success: SuccessResult? = null,
    @ProtoNumber(2) val error: ErrorResult? = null,
)

@Serializable
data class ErrorResult(
    @ProtoNumber(2) val englishPopup: Popup? = null,
    @ProtoNumber(3) val spanishPopup: Popup? = null,
) {
    fun langPopup(langCode: Int): Popup? = when (langCode) {
        LANGUAGE_SPANISH -> spanishPopup ?: englishPopup
        else -> englishPopup
    }
}

@Serializable
data class Popup(
    @ProtoNumber(1) val subject: String = "",
    @ProtoNumber(2) val body: String = "",
)

@Serializable
data class SuccessResult(
    @ProtoNumber(8) val titleDetailView: TitleDetailView? = null,
    @ProtoNumber(10) val mangaViewer: MangaViewer? = null,
    @ProtoNumber(25) val allTitlesView: AllTitlesView? = null,
    @ProtoNumber(35) val allTitlesViewV3: AllTitlesViewV3? = null,
    @ProtoNumber(37) val titleRankingView: TitleRankingView? = null,
    @ProtoNumber(38) val webHomeView: WebHomeView? = null,
)

@Serializable
data class AllTitlesViewV3(
    @ProtoNumber(2) val tags: List<TagName> = emptyList(),
    @ProtoNumber(3) val titles: List<AllTitlesV3Entry> = emptyList(),
)

@Serializable
data class AllTitlesV3Entry(
    @ProtoNumber(2) val title: Title,
    @ProtoNumber(3) val genres: List<TagName> = emptyList(),
)

@Serializable
data class TitleRankingView(
    @ProtoNumber(3) val rankedTitles: List<RankedTitle> = emptyList(),
)

@Serializable
data class RankedTitle(
    @ProtoNumber(2) val titles: List<Title> = emptyList(),
)

@Serializable
data class AllTitlesView(
    @ProtoNumber(1) val allTitlesGroup: List<AllTitlesGroup> = emptyList(),
)

@Serializable
data class AllTitlesGroup(
    @ProtoNumber(2) val titles: List<Title> = emptyList(),
)

@Serializable
data class WebHomeView(
    @ProtoNumber(2) val groups: List<UpdatedTitleGroup> = emptyList(),
    @ProtoNumber(7) val featured: FeaturedUpdate? = null,
)

@Serializable
data class FeaturedUpdate(
    @ProtoNumber(2) val title: UpdatedTitle,
)

@Serializable
data class UpdatedTitleGroup(
    @ProtoNumber(2) val titles: List<UpdatedTitle> = emptyList(),
)

@Serializable
data class UpdatedTitle(
    @ProtoNumber(3) val latestChapters: List<LatestChapter> = emptyList(),
    @ProtoNumber(6) val updatedAt: Int = 0,
)

@Serializable
data class LatestChapter(
    @ProtoNumber(1) val title: Title,
)

@Serializable
data class Title(
    @ProtoNumber(1) val titleId: Int = 0,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val author: String? = null,
    @ProtoNumber(4) val portraitImageUrl: String = "",
    @ProtoNumber(7) val language: Int = LANGUAGE_ENGLISH,
)

@Serializable
data class TitleDetailView(
    @ProtoNumber(1) val title: Title,
    @ProtoNumber(3) val overview: String = "",
    @ProtoNumber(7) val viewingPeriodDescription: String = "",
    @ProtoNumber(8) val nonAppearanceInfo: String = "",
    @ProtoNumber(28) val chapterListGroup: List<ChapterListGroup> = emptyList(),
    @ProtoNumber(31) val genreList: List<TagName> = emptyList(),
) {
    val chapterList: List<Chapter>
        get() = chapterListGroup.flatMap { it.firstChapterList + it.lastChapterList }
}

@Serializable
data class TagName(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val slug: String = "",
)

@Serializable
data class ChapterListGroup(
    @ProtoNumber(2) val firstChapterList: List<Chapter> = emptyList(),
    @ProtoNumber(4) val lastChapterList: List<Chapter> = emptyList(),
)

@Serializable
data class Chapter(
    @ProtoNumber(2) val chapterId: Int,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val subTitle: String? = null,
    @ProtoNumber(6) val startTimeStamp: Int = 0,
) {
    val isExpired: Boolean get() = subTitle == null
}

@Serializable
data class MangaViewer(
    @ProtoNumber(1) val pages: List<MangaPlusPage> = emptyList(),
    @ProtoNumber(9) val titleId: Int? = null,
    @ProtoNumber(19) val viewToken: String? = null,
)

@Serializable
data class MangaPlusPage(
    @ProtoNumber(1) val mangaPage: MPImagePage? = null,
)

@Serializable
data class MPImagePage(
    @ProtoNumber(1) val imageUrl: String = "",
    @ProtoNumber(5) val encryptionKey: String? = null,
)

const val LANGUAGE_ENGLISH = 0
const val LANGUAGE_SPANISH = 1
const val LANGUAGE_FRENCH = 2
const val LANGUAGE_INDONESIAN = 3
const val LANGUAGE_PORTUGUESE_BR = 4
const val LANGUAGE_RUSSIAN = 5
const val LANGUAGE_THAI = 6
const val LANGUAGE_GERMAN = 7
const val LANGUAGE_VIETNAMESE = 9

internal abstract class MangaPlusParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val internalLangName: String,
    private val internalLangCode: Int,
) : SinglePageMangaParser(context, source), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("mangaplus.shueisha.co.jp")

    private val apiUrl = "https://jumpg-webapi.tokyo-cdn.com/api"
    private val session = UUID.randomUUID().toString()
    private val imageSemaphore = Semaphore(1)

    private val detailsCacheLock = Any()
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    private val preferredServerKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            "low" to "Low quality",
            "high" to "High quality",
            "super_high" to "Super high quality",
        ),
        defaultValue = "high",
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(preferredServerKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(availableTags = fetchGenreTags())
    
    private suspend fun apiCall(path: String): MangaPlusResponse {
        val url = "$apiUrl$path".toHttpUrl()
        val headers = Headers.headersOf("SESSION-TOKEN", session)
        return webClient.httpGet(url, headers).parseAsProto()
    }

    private fun MangaPlusResponse.successOrThrow(): SuccessResult {
        return success ?: run {
            val popup = error?.langPopup(internalLangCode)
            val message = popup?.body?.takeIf { it.isNotEmpty() }
                ?: popup?.subject?.takeIf { it.isNotEmpty() }
                ?: "Unknown MangaPlus error"
            throw IOException(message)
        }
    }
    
    override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query.orEmpty().trim()

        if (filter.tags.isNotEmpty()) {
            return searchWithGenres(filter.tags, query)
        }

        if (query.isNotEmpty()) {
            return searchByText(query)
        }

        return when (order) {
            SortOrder.POPULARITY -> getPopularity()
            SortOrder.UPDATED -> getLatestUpdates()
            SortOrder.ALPHABETICAL -> getAllTitlesAlphabetical()
            else -> emptyList()
        }
    }

    private suspend fun searchWithGenres(tags: Collection<MangaTag>, query: String): List<Manga> {
        val resp = apiCall("/title_list/all_v3?type=serializing&lang=$internalLangName&clang=$internalLangName")
        val entries = resp.success?.allTitlesViewV3?.titles.orEmpty()
        return entries
            .filter { entry ->
                tags.any { tag -> entry.genres.any { it.slug == tag.key } } &&
                        (query.isEmpty() ||
                                entry.title.name.contains(query, true) ||
                                entry.title.author?.contains(query, true) == true)
            }
            .map { it.title }
            .filterByLang()
            .map { it.toManga() }
    }

    private suspend fun searchByText(query: String): List<Manga> {
        val resp = apiCall("/title_list/allV2")
        return resp.success?.allTitlesView?.allTitlesGroup?.flatMap { it.titles }.orEmpty()
            .filter { it.name.contains(query, true) || it.author?.contains(query, true) == true }
            .filterByLang()
            .map { it.toManga() }
    }

    private suspend fun getPopularity(): List<Manga> {
        val resp = apiCall("/title_list/rankingV2?lang=$internalLangName&type=hottest&clang=$internalLangName")
        return resp.success?.titleRankingView?.rankedTitles?.flatMap { it.titles }
            .orEmpty().filterByLang().map { it.toManga() }
    }

    private suspend fun getLatestUpdates(): List<Manga> {
        val resp = apiCall("/web/web_homeV4?lang=$internalLangName&clang=$internalLangName")
        val home = resp.success?.webHomeView ?: return emptyList()
        val entries = home.groups.flatMap { it.titles } + listOfNotNull(home.featured?.title)
        return entries
            .sortedByDescending { it.updatedAt }
            .flatMap { it.latestChapters }
            .map { it.title }
            .filterByLang()
            .map { it.toManga() }
    }

    private suspend fun getAllTitlesAlphabetical(): List<Manga> {
        val resp = apiCall("/title_list/allV2")
        return resp.success?.allTitlesView?.allTitlesGroup?.flatMap { it.titles }.orEmpty()
            .filterByLang()
            .sortedBy { it.name.lowercase() }
            .map { it.toManga() }
    }

    private fun Title.toManga() = Manga(
        id = generateUid(titleId.toString()),
        url = titleId.toString(),
        publicUrl = "/titles/$titleId".toAbsoluteUrl(domain),
        title = name,
        coverUrl = portraitImageUrl,
        altTitles = emptySet(),
        authors = author?.split("/")?.joinToString(", ") { it.trim() }?.let { setOf(it) } ?: emptySet(),
        contentRating = null,
        rating = RATING_UNKNOWN,
        state = null,
        source = source,
        tags = emptySet(),
    )

    private fun List<Title>.filterByLang(): List<Title> =
        filter { it.titleId != 0 && it.language == internalLangCode }.distinctBy { it.titleId }
    
    override suspend fun getDetails(manga: Manga): Manga {
        synchronized(detailsCacheLock) {
            detailsCache[manga.url]?.let { return it }
        }

        val resp = apiCall("/title_detailV3?title_id=${manga.url}&clang=$internalLangName")
        val detail = resp.successOrThrow().titleDetailView
            ?: throw IOException("Missing titleDetailView")

        val isCompleted = detail.nonAppearanceInfo.contains(COMPLETED_REGEX) ||
                detail.viewingPeriodDescription.contains("latest 0 chapters")
        val isHiatus = detail.nonAppearanceInfo.contains(HIATUS_REGEX)

        val tags = detail.genreList.mapNotNullTo(linkedSetOf()) { genre ->
            genre.name.takeIf { it.isNotBlank() }?.let {
                MangaTag(key = genre.slug, title = genre.name, source = source)
            }
        }

        val chapters = detail.chapterList
            .filter { !it.isExpired }
            .map { chapter ->
                MangaChapter(
                    id = generateUid(chapter.chapterId.toString()),
                    url = chapter.chapterId.toString(),
                    title = "${chapter.name} - ${chapter.subTitle}",
                    number = chapter.name.substringAfter("#").toFloatOrNull() ?: -1f,
                    volume = 0,
                    uploadDate = chapter.startTimeStamp * 1000L,
                    branch = null,
                    scanlator = null,
                    source = source,
                )
            }

        val result = manga.copy(
            title = detail.title.name,
            coverUrl = detail.title.portraitImageUrl,
            authors = detail.title.author
                ?.split("/")
                ?.joinToString(", ") { it.trim() }
                ?.let { setOf(it) }
                ?: emptySet(),
            description = listOfNotNull(
                detail.overview.takeIf { it.isNotEmpty() },
                detail.viewingPeriodDescription.takeIf { it.isNotEmpty() },
            ).joinToString("\n\n"),
            state = when {
                isCompleted -> MangaState.FINISHED
                isHiatus -> MangaState.PAUSED
                else -> MangaState.ONGOING
            },
            tags = tags,
            chapters = chapters,
        )

        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val quality = config[preferredServerKey]
        val resp = apiCall(
            "/manga_viewer_v3?chapter_id=${chapter.url}&split=yes&img_quality=$quality&clang=$internalLangName",
        )
        val viewer = resp.successOrThrow().mangaViewer ?: return emptyList()
        val viewToken = viewer.viewToken.orEmpty()
        return viewer.pages.mapNotNull { it.mangaPage }.mapNotNull { image ->
            if (image.imageUrl.isBlank()) return@mapNotNull null
            val encryptionKey = image.encryptionKey
            val separator = if (image.imageUrl.contains("?")) "&" else "?"
            val fullUrl = buildString {
                append(image.imageUrl)
                append(separator).append("vtoken=").append(viewToken)
                if (encryptionKey != null) append("#").append(encryptionKey)
            }
            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source,
            )
        }
    }
    
    @Volatile
    private var tagsCache: Set<MangaTag>? = null
    private val tagsMutex = Mutex()

    private suspend fun fetchGenreTags(): Set<MangaTag> {
        tagsCache?.let { return it }
        return tagsMutex.withLock {
            tagsCache ?: run {
                val resp = apiCall(
                    "/title_list/all_v3?type=serializing&lang=$internalLangName&clang=$internalLangName",
                )
                val tags = resp.success?.allTitlesViewV3?.tags.orEmpty()
                    .mapNotNull { tag ->
                        tag.name.takeIf { it.isNotBlank() }?.let {
                            MangaTag(key = tag.slug, title = tag.name, source = source)
                        }
                    }
                    .toSet()
                tagsCache = tags
                tags
            }
        }
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val host = url.host

        return if (host.startsWith("jumpg-assets")) {
            imageSemaphore.acquire()
            try {
                processImageRequest(chain, request, url)
            } finally {
                imageSemaphore.release()
            }
        } else {
            processImageRequest(chain, request, url)
        }
    }

    private fun processImageRequest(
        chain: Interceptor.Chain,
        request: Request,
        url: okhttp3.HttpUrl,
    ): Response {
        val encryptionKey = url.fragment
        val vtoken = url.queryParameter("vtoken")

        val newUrl = url.newBuilder().removeAllQueryParameters("vtoken").build()
        val newRequest = request.newBuilder()
            .url(newUrl)
            .apply { if (vtoken != null) header("Plus-Vw-Token", vtoken) }
            .build()

        val response = chain.proceed(newRequest)

        if (encryptionKey.isNullOrEmpty()) return response

        val keyStream = encryptionKey.chunked(2).map { it.toInt(16) }
        val contentType = response.header("Content-Type") ?: "image/jpeg"
        val bytes = response.body.bytes()
        val decrypted = bytes.mapIndexed { i, byte ->
            (byte.toInt() xor keyStream[i % keyStream.size]).toByte()
        }.toByteArray()

        return response.newBuilder()
            .body(decrypted.toResponseBody(contentType.toMediaTypeOrNull()))
            .build()
    }
    
    private companion object {
        val COMPLETED_REGEX = Regex("completado|completed?|completo", RegexOption.IGNORE_CASE)
        val HIATUS_REGEX = Regex("on a hiatus", RegexOption.IGNORE_CASE)
    }

    @MangaSourceParser("MANGAPLUSPARSER_EN", "MANGA Plus (English)", "en")
    class English(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_EN, "eng", LANGUAGE_ENGLISH)

    @MangaSourceParser("MANGAPLUSPARSER_ES", "MANGA Plus (Spanish)", "es")
    class Spanish(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_ES, "esp", LANGUAGE_SPANISH)

    @MangaSourceParser("MANGAPLUSPARSER_FR", "MANGA Plus (French)", "fr")
    class French(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_FR, "fra", LANGUAGE_FRENCH)

    @MangaSourceParser("MANGAPLUSPARSER_ID", "MANGA Plus (Indonesian)", "id")
    class Indonesian(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_ID, "ind", LANGUAGE_INDONESIAN)

    @MangaSourceParser("MANGAPLUSPARSER_PTBR", "MANGA Plus Portuguese (Brazil)", "pt")
    class Portuguese(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_PTBR, "ptb", LANGUAGE_PORTUGUESE_BR)

    @MangaSourceParser("MANGAPLUSPARSER_RU", "MANGA Plus (Russian)", "ru")
    class Russian(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_RU, "rus", LANGUAGE_RUSSIAN)

    @MangaSourceParser("MANGAPLUSPARSER_TH", "MANGA Plus (Thai)", "th")
    class Thai(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_TH, "tha", LANGUAGE_THAI)

    @MangaSourceParser("MANGAPLUSPARSER_VI", "MANGA Plus (Vietnamese)", "vi")
    class Vietnamese(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_VI, "vie", LANGUAGE_VIETNAMESE)

    @MangaSourceParser("MANGAPLUSPARSER_DE", "MANGA Plus (German)", "de")
    class German(context: MangaLoaderContext) :
        MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_DE, "deu", LANGUAGE_GERMAN)
}
