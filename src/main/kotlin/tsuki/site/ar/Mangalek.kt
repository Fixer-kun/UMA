package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.network.UserAgents
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource
import tsuki.model.MangaChapter
import tsuki.model.Manga
import tsuki.model.MangaTag

import tsuki.util.attrAsRelativeUrl
import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("MANGALEK", "Mangalek", "ar")
internal class Mangalek(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGALEK, "mangalik.net") {

    override val datePattern = "MMMM dd, yyyy"
    override val stylePage = ""
    override val sourceLocale = Locale("ar")

    override val postReq = true

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

    private val englishDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun parseChapterDate(dateFormat: DateFormat, date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        super.parseChapterDate(dateFormat, date).takeIf { it != 0L }?.let { return it }
        runCatching { englishDateFormat.parse(date)?.time }.getOrNull()
            ?.takeIf { it != 0L }?.let { return it }
        return runCatching { isoDateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val result = super.getDetails(manga)
        return if (result.title.lowercase().contains("access denied"))
            result.copy(title = manga.title) else result
    }

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
        val mangaId = document.selectFirst("div#manga-chapters-holder")
            ?.attr("data-id")
            ?.takeIf { it.isNotBlank() }
            ?: return parseChapterElements(document.body()) // fallback

        val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php".toHttpUrl()
        val postData = "action=manga_get_chapters&manga=$mangaId"

        val ajaxDoc = webClient.httpPost(ajaxUrl, postData).parseHtml()
        val chapters = parseChapterElements(ajaxDoc.body())

        return chapters
    }

    private fun parseChapterElements(root: Element): List<MangaChapter> {
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        return root.select(selectChapter).map { li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
                ?: li.selectFirst(selectDate)?.text()
            val name = a.selectFirst("p")?.text() ?: a.ownText()
            MangaChapter(
                id = generateUid(href),
                url = href + stylePage,
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                branch = null,
                uploadDate = parseChapterDate(dateFormat, dateText),
                scanlator = null,
                source = source,
            )
        }.sortedBy { it.number }
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
        return doc.selectFirst("div.genres")
            ?.select("a")
            .orEmpty()
            .mapNotNull { a ->
                val genre = a.ownText()
                if (genre.isBlank()) null
                else MangaTag(key = genre.lowercase(), title = genre, source = source)
            }
            .toSet()
    }
}
