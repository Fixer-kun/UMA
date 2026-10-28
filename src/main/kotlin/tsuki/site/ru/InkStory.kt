package tsuki.site.ru

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

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
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.json.asTypedList
import tsuki.util.json.getFloatOrDefault
import tsuki.util.json.getIntOrDefault
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSON
import tsuki.util.json.mapJSONToSet
import tsuki.util.mapNotNullToSet
import tsuki.util.nl2br
import tsuki.util.parseJson
import tsuki.util.parseJsonArray
import tsuki.util.parseSafe
import tsuki.util.toTitleCase
import tsuki.util.urlBuilder

import androidx.collection.ArrayMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGAWTF", "InkStory", "ru")
internal class InkStory(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAWTF, 20), Interceptor {

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(
            SortOrder.POPULARITY,
            SortOrder.RATING,
            SortOrder.UPDATED,
            SortOrder.NEWEST,
        )

    override val configKeyDomain = ConfigKey.Domain("inkstory.net")

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(
            MangaState.UPCOMING,
            MangaState.PAUSED,
            MangaState.ONGOING,
            MangaState.FINISHED,
        ),
        availableContentRating = EnumSet.allOf(ContentRating::class.java),
    )

    init {
        paginator.firstPage = 0
        searchPaginator.firstPage = 0
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url =
            urlBuilder("api")
                .addPathSegment("v2")
                .addPathSegment("books")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("size", pageSize.toString())
                .addQueryParameter("type", "COMIC")
        when {
            filter.query.isNullOrEmpty() -> {
                url.addQueryParameter(
                    "sort",
                    when (order) {
                        SortOrder.UPDATED -> "updatedAt,desc"
                        SortOrder.POPULARITY -> "viewsCount,desc"
                        SortOrder.RATING -> "likesCount,desc"
                        SortOrder.NEWEST -> "createdAt,desc"
                        else -> throw IllegalArgumentException("Unsupported $order")
                    },
                )
                if (filter.tags.isNotEmpty()) {
                    url.addQueryParameter("labelsInclude", filter.tags.joinToString(",") { it.key })
                }
                if (filter.tagsExclude.isNotEmpty()) {
                    url.addQueryParameter("labelsExclude", filter.tagsExclude.joinToString(",") { it.key })
                }
                if (filter.states.isNotEmpty()) {
                    url.addQueryParameter(
                        "status",
                        filter.states.joinToString(",") {
                            when (it) {
                                MangaState.ONGOING -> "ONGOING"
                                MangaState.FINISHED -> "DONE"
                                MangaState.ABANDONED -> ""
                                MangaState.PAUSED -> "FROZEN"
                                MangaState.UPCOMING -> "ANNOUNCE"
                                else -> throw IllegalArgumentException("$it not supported")
                            }
                        },
                    )
                }
                if (filter.contentRating.isNotEmpty()) {
                    url.addQueryParameter(
                        "contentStatus",
                        filter.contentRating.joinToString(",") {
                            when (it) {
                                ContentRating.SAFE -> "SAFE"
                                ContentRating.SUGGESTIVE -> "UNSAFE,EROTIC"
                                ContentRating.ADULT -> "PORNOGRAPHIC"
                            }
                        },
                    )
                }
            }

            else -> {
                url.addQueryParameter("search", filter.query)
            }
        }
        val ja = webClient.httpGet(url.build()).parseJsonArray()
        return ja.mapJSON { jo -> jo.toManga() }
    }

    override suspend fun getDetails(manga: Manga): Manga =
        coroutineScope {
            val chaptersDeferred = async { getChapters(manga.url) }
            val url =
                urlBuilder("api")
                    .addPathSegment("v2")
                    .addPathSegment("books")
                    .addPathSegment(manga.url)
            val jo = webClient.httpGet(url.build()).parseJson()
            val isNsfwSource = jo.getStringOrNull("contentStatus").isNsfw()
            Manga(
                id = generateUid(jo.getString("id")),
                title = jo.getJSONObject("name").getString("ru"),
                altTitles = setOfNotNull(jo.getJSONObject("name").getStringOrNull("en")),
                url = jo.getString("id"),
                publicUrl = "https://$domain/manga/${jo.getString("slug")}",
                rating = jo.getFloatOrDefault("averageRating", -10f) / 10f,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = jo.getString("poster"),
                tags = jo.getJSONArray("labels").mapJSONToSet { it.toMangaTag() },
                state = jo.getStringOrNull("status")?.toMangaState(),
                authors = jo.getJSONArray("relations").asTypedList<JSONObject>().mapNotNullToSet {
                    if (it.getStringOrNull("type") == "AUTHOR") {
                        it.getJSONObject("publisher").getStringOrNull("name")
                    } else {
                        null
                    }
                },
                source = source,
                largeCoverUrl = null,
                description = jo.getString("description").nl2br(),
                chapters = chaptersDeferred.await(),
            )
        }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url =
            urlBuilder("api")
                .addPathSegment("v2")
                .addPathSegment("chapters")
                .addPathSegment(chapter.url)
        val json = webClient.httpGet(url.build()).parseJson()
        val pagesArray = json.getJSONArray("pages")
        val list = mutableListOf<Pair<Int, MangaPage>>()
        for (i in 0 until pagesArray.length()) {
            val jo = pagesArray.getJSONObject(i)
            val index = jo.optInt("index", Int.MAX_VALUE)
            val imageUrl = jo.getString("image")
            list.add(
                index to MangaPage(
                    id = generateUid(jo.getString("id")),
                    url = normalizeImageUrl(imageUrl),
                    preview = null,
                    source = source,
                )
            )
        }
        return list.sortedBy { it.first }.map { it.second }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = urlBuilder("api").addPathSegment("v2").addPathSegment("labels")
        val json = webClient.httpGet(url.build()).parseJsonArray()
        return json.mapJSONToSet { jo ->
            MangaTag(
                title = jo.getString("name").toTitleCase(sourceLocale),
                key = jo.getString("slug"),
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private suspend fun getChapters(mangaId: String): List<MangaChapter> {
        val url =
            urlBuilder("api")
                .addPathSegment("v2")
                .addPathSegment("chapters")
                .addQueryParameter("bookId", mangaId)
        val ja = webClient.httpGet(url.build()).parseJsonArray()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)
        val branches = ArrayMap<String, String>()
        return ja
            .mapJSON { jo ->
                val number = jo.getFloatOrDefault("number", 0f)
                val volume = jo.getIntOrDefault("volume", 0)
                val branchId = jo.getString("branchId")
                MangaChapter(
                    id = generateUid(jo.getString("id")),
                    title = jo.getStringOrNull("name"),
                    number = number,
                    volume = volume,
                    url = jo.getString("id"),
                    scanlator = null,
                    uploadDate = dateFormat.parseSafe(jo.getString("createdAt")),
                    branch = branches.getOrPut(branchId) { getBranchName(branchId) },
                    source = source,
                )
            }.reversed()
    }

    private suspend fun getBranchName(id: String): String? {
        val url =
            urlBuilder("api")
                .addPathSegment("v2")
                .addPathSegment("branches")
                .addPathSegment(id)
        val json = webClient.httpGet(url.build()).parseJson()
        return json.getJSONArray("publishers").mapJSONToSet { it.getStringOrNull("name") }.firstOrNull()
    }

    private fun String.toMangaState() =
        when (this.uppercase(Locale.ROOT)) {
            "DONE" -> MangaState.FINISHED
            "ONGOING" -> MangaState.ONGOING
            "FROZEN" -> MangaState.PAUSED
            "ANNOUNCE" -> MangaState.UPCOMING
            else -> null
        }

    private fun String?.isNsfw() =
        this.equals("EROTIC", ignoreCase = true) ||
                this.equals("PORNOGRAPHIC", ignoreCase = true)

    private fun JSONObject.toMangaTag() =
        MangaTag(
            title = getString("name").toTitleCase(sourceLocale),
            key = getString("slug"),
            source = source,
        )

    private fun JSONObject.toManga(): Manga {
        val isNsfwSource = getStringOrNull("contentStatus").isNsfw()
        return Manga(
            id = generateUid(getString("id")),
            title = getJSONObject("name").getString("ru"),
            altTitles = setOfNotNull(getJSONObject("name").getStringOrNull("en")),
            url = getString("id"),
            publicUrl = "https://$domain/manga/${getString("slug")}",
            rating = getFloatOrDefault("averageRating", -10f) / 10f,
            contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            coverUrl = getString("poster"),
            tags = setOf(),
            state = getStringOrNull("status")?.toMangaState(),
            authors = emptySet(),
            source = source,
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response

        val contentType = response.body?.contentType()
        if (contentType?.subtype == "json") return response

        if (detectImageCodec(response.request.url.toString()) != ImageCodec.XOR) {
            return response
        }

        val source = response.body?.source() ?: return response
        val buffer = Buffer()
        source.readAll(buffer)

        if (buffer.size < MIN_IMAGE_SIGNATURE_SIZE) {
            return response.newBuilder()
                .body(buffer.asResponseBody(contentType, buffer.size))
                .build()
        }

        val peek = buffer.peek().readByteArray(MIN_IMAGE_SIGNATURE_SIZE.toLong())
        if (looksLikeImage(peek) != null) {
            return response.newBuilder()
                .body(buffer.asResponseBody(contentType, buffer.size))
                .build()
        }

        val decryptedHeader = ByteArray(MIN_IMAGE_SIGNATURE_SIZE) { index ->
            (peek[index].toInt() xor SECRET_KEY_BYTES[index % SECRET_KEY_BYTES.size].toInt()).toByte()
        }

        val detectedType = looksLikeImage(decryptedHeader)
            ?: return response.newBuilder()
                .body(buffer.asResponseBody(contentType, buffer.size))
                .build()

        val payload = buffer.readByteArray()
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor SECRET_KEY_BYTES[i % SECRET_KEY_BYTES.size].toInt()).toByte()
        }

        val mediaType = detectedType.toMediaType()
        return response.newBuilder()
            .body(payload.toResponseBody(mediaType))
            .build()
    }

    private fun detectImageCodec(imageUrl: String): ImageCodec? {
        val fileName = imageUrl.substringAfterLast('/').substringBefore('?')
        val baseName = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        if (baseName.length != IMAGE_NAME_LENGTH) return null
        return when (baseName.getOrNull(IMAGE_MODE_INDEX)) {
            's' -> ImageCodec.SEC
            'x' -> ImageCodec.XOR
            else -> null
        }
    }

    private fun normalizeImageUrl(imageUrl: String): String =
        if (detectImageCodec(imageUrl) == ImageCodec.SEC) {
            replaceFileNameMode(imageUrl)
        } else {
            imageUrl
        }

    private fun replaceFileNameMode(imageUrl: String, replacementMode: Char = 'x'): String {
        val lastSlash = imageUrl.lastIndexOf('/')
        if (lastSlash == -1) return imageUrl
        val modeIndex = lastSlash + 1 + IMAGE_MODE_INDEX
        if (modeIndex >= imageUrl.length) return imageUrl
        val chars = imageUrl.toCharArray()
        chars[modeIndex] = replacementMode
        return String(chars)
    }

    private fun looksLikeImage(payload: ByteArray): String? {
        if (payload.size < MIN_IMAGE_SIGNATURE_SIZE) return null

        val isJpeg = payload[0] == 0xFF.toByte() && payload[1] == 0xD8.toByte() && payload[2] == 0xFF.toByte()
        if (isJpeg) return "image/jpeg"

        val isPng = payload[0] == 0x89.toByte() && payload[1] == 0x50.toByte() &&
                payload[2] == 0x4E.toByte() && payload[3] == 0x47.toByte()
        if (isPng) return "image/png"

        val isGif = payload[0] == 0x47.toByte() && payload[1] == 0x49.toByte() &&
                payload[2] == 0x46.toByte() && payload[3] == 0x38.toByte()
        if (isGif) return "image/gif"

        val isWebp = payload[0] == 0x52.toByte() && payload[1] == 0x49.toByte() &&
                payload[2] == 0x46.toByte() && payload[3] == 0x46.toByte() &&
                payload[8] == 0x57.toByte() && payload[9] == 0x45.toByte() &&
                payload[10] == 0x42.toByte() && payload[11] == 0x50.toByte()
        if (isWebp) return "image/webp"

        val isAvif = payload[4] == 0x66.toByte() && payload[5] == 0x74.toByte() &&
                payload[6] == 0x79.toByte() && payload[7] == 0x70.toByte() &&
                payload[8] == 0x61.toByte() && payload[9] == 0x76.toByte() &&
                payload[10] == 0x69.toByte()
        if (isAvif) return "image/avif"

        return null
    }

    private enum class ImageCodec { SEC, XOR }

    private companion object {
        const val MIN_IMAGE_SIGNATURE_SIZE = 12
        const val IMAGE_NAME_LENGTH = 36
        const val IMAGE_MODE_INDEX = 14
        const val SECRET_KEY = "UySkp0BzPhwlvP2V"
        val SECRET_KEY_BYTES = SECRET_KEY.toByteArray()
    }
}
