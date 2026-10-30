package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("LHTRANSLATION", "LHTranslation", "en")
internal class LHTranslation(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.LHTRANSLATION, "lhtranslation.net")
