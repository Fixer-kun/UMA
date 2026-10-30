package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.MangaParserAuthProvider
import tsuki.parsers.MangaThemesia
import tsuki.exception.ParseException

import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.toAbsoluteUrl

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Locale

@MangaSourceParser("KOMIKTAP", "Komiktap", "id", ContentType.HENTAI)
internal class Komiktap(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.KOMIKTAP, "komiktap.info", pageSize = 25), MangaParserAuthProvider, Interceptor {

    private val selectScript = "script[src^='data:text/javascript;base64,']"
    private val selectTestScript = "script:containsData(ts_reader.run)"
    private val selectPage = "div#readerarea img"

    override val authUrl: String get() = "https://$domain/"

    override suspend fun isAuthorized(): Boolean {
        val response = webClient.httpGet("https://$domain/")
        return response.headers["x-sucuri-cache"] != null ||
                response.headers["x-sucuri-id"] == null
    }

    override suspend fun getUsername(): String = ""

    private val idLocale = Locale("id", "ID")
    private val idDateFormats = listOf(
        "MMMM dd, yyyy", "dd MMMM yyyy", "MMMM d, yyyy", "d MMMM yyyy",
        "dd/MM/yyyy", "yyyy-MM-dd", "dd-MM-yyyy",
    )

    override fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val text = date.trim().lowercase(idLocale)
        val now = Calendar.getInstance()
        val n = Regex("""(\d+)""").find(text)?.value?.toIntOrNull()

        when {
            text.startsWith("kemarin") -> return now.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
            text.startsWith("hari ini") || text == "baru saja" -> return now.timeInMillis
            ("lalu" in text || text.endsWith("ago")) && n != null -> {
                val field = when {
                    "detik" in text -> Calendar.SECOND
                    "menit" in text -> Calendar.MINUTE
                    "jam" in text -> Calendar.HOUR
                    "hari" in text -> Calendar.DAY_OF_MONTH
                    "minggu" in text -> Calendar.WEEK_OF_YEAR
                    "bulan" in text -> Calendar.MONTH
                    "tahun" in text -> Calendar.YEAR
                    else -> return 0L
                }
                return now.apply { add(field, -n) }.timeInMillis
            }
        }

        for (pattern in idDateFormats) {
            val parsed = runCatching { SimpleDateFormat(pattern, idLocale).apply { isLenient = false }.parse(date.trim()) }
                .getOrNull()
            if (parsed != null) return parsed.time
        }
        return super.parseChapterDate(date)
    }

    override fun parseAltTitles(doc: Document, table: Element?, hasTable: Boolean): Set<String> {
        return buildSet {
            addAll(super.parseAltTitles(doc, table, hasTable))

            doc.selectFirst(".seriestualt")
                ?.text()
                ?.takeIf { it.isNotBlank() }
                ?.let { addAll(splitAltTitles(it)) }
        }
    }

    private val excludePrefix = "xtype:"

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val base = super.getFilterOptions()
        val excludeTags = base.availableContentTypes.map { type ->
            MangaTag(
                key = excludePrefix + type.name,
                title = "Exclude: " + type.name.lowercase().replaceFirstChar { it.uppercase() },
                source = source,
            )
        }
        return MangaListFilterOptions(
            availableTags = base.availableTags + excludeTags,
            availableStates = base.availableStates,
            availableContentTypes = base.availableContentTypes,
        )
    }

    private suspend fun listOneRequest(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> =
        super.getListPage(page, order, filter)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val excluded = filter.tags
            .filter { it.key.startsWith(excludePrefix) }
            .mapNotNull { runCatching { ContentType.valueOf(it.key.removePrefix(excludePrefix)) }.getOrNull() }
            .toSet()
        val realTags = filter.tags.filterNot { it.key.startsWith(excludePrefix) }.toSet()

        if (excluded.isEmpty() && filter.types.size <= 1) {
            return listOneRequest(page, order, filter.copy(tags = realTags))
        }

        val pool = filter.types.ifEmpty { super.getFilterOptions().availableContentTypes }
        val wanted = pool - excluded
        if (wanted.isEmpty()) return emptyList()

        val results = coroutineScope {
            wanted.map { type ->
                async { listOneRequest(page, order, filter.copy(types = setOf(type), tags = realTags)) }
            }.awaitAll()
        }

        val merged = LinkedHashMap<Long, Manga>()
        val longest = results.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (list in results) list.getOrNull(i)?.let { merged.putIfAbsent(it.id, it) }
        }
        val list = merged.values.toList()
        return when (order) {
            SortOrder.ALPHABETICAL -> list.sortedBy { it.title.lowercase() }
            SortOrder.ALPHABETICAL_DESC -> list.sortedByDescending { it.title.lowercase() }
            else -> list
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl).parseHtml()

        val test = doc.select(selectTestScript)
        if (test.isEmpty()) {
            return doc.select(selectPage).mapNotNull { img ->
                val url = img.imgAttr().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MangaPage(id = generateUid(url), url = url, preview = null, source = source)
            }
        }

        val images = try {
            val base64Script = doc.select(selectScript)
            var decode = ""
            for (elem in base64Script) {
                val src = elem.attr("src")
                if (src.startsWith("data:text/javascript;base64,")) {
                    decode = Base64.getDecoder()
                        .decode(src.removePrefix("data:text/javascript;base64,"))
                        .toString(Charsets.UTF_8)
                    if (decode.startsWith("ts_reader.run")) break
                }
            }

            if (decode.startsWith("ts_reader.run")) {
                extractImagesFromScript(decode)
            } else {
                val scriptElem = doc.selectFirstOrThrow(selectTestScript)
                extractImagesFromScript(scriptElem.data())
            }
        } catch (e: Exception) {
            throw ParseException("Failed to extract images: ${e.message}", chapterUrl)
        }

        return (0 until images.length()).map { i ->
            val url = images.getString(i)
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    private fun extractImagesFromScript(scriptBody: String): JSONArray {
        val json = scriptBody.substringAfter('(').substringBeforeLast(')')
        return JSONObject(json)
            .getJSONArray("sources")
            .getJSONObject(0)
            .getJSONArray("images")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val mime = response.headers["Content-Type"]
        if (response.isSuccessful && mime == "application/octet-stream") {
            val newBody = response.body.source().asResponseBody("image/jpeg".toMediaTypeOrNull()!!)
            return response.newBuilder().body(newBody).build()
        }
        return response
    }
}
