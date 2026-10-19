package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.parsers.MangaThemesia

import org.jsoup.nodes.Element

@MangaSourceParser("ATHREASCANS", "AthreaScans", "en")
internal class AthreaScans(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.ATHREASCANS, "athreascans.com") {

    override fun transformChapterName(element: Element, name: String): String {
        return if (element.selectFirst("a[data-bs-target='#lockedChapterModal']") != null) {
            "🔒 $name"
        } else {
            name
        }
    }
}
