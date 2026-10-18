package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

@MangaSourceParser("SIIKOMIK", "Siikomik", "id")
internal class Siikomik(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.SIIKOMIK, "siikomik.id") {

    override val listUrl = "semua-komik/"
    override val referer: String get() = "https://$domain/"

    override val selectGenres = "div.manga-pills-scroll a.manga-filter-box"

    override fun extractGenreKey(href: String): String? {
        val slug = href.substringAfter("?genre=", "").substringBefore("&")
        return slug.takeIf { it.isNotEmpty() }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath.lowercase()
        val isImage = listOf(".webp", ".jpg", ".jpeg", ".png", ".gif", ".avif", ".svg")
            .any { path.endsWith(it) }
        return if (isImage) {
            chain.proceed(
                request.newBuilder()
                    .header("Referer", "https://$domain/")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .build()
            )
        } else {
            chain.proceed(request)
        }
    }

    override fun transformChapterName(element: Element, name: String): String {
        return if (element.hasClass("premium") || element.hasClass("premium-block")) {
            "🔒 $name"
        } else {
            name
        }
    }
}
