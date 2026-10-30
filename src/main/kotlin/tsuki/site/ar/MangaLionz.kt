package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

import java.util.Locale

@MangaSourceParser("MANGALIONZ", "Manga Lionz", "ar")
internal class MangaLionz(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGALIONZ, "manga-lionz.org") {
    override val sourceLocale: Locale = Locale("ar")
    override val datePattern: String = "MMMM d, yyyy"
}
