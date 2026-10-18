package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.IkenParser

import tsuki.model.MangaParserSource

@MangaSourceParser("AZORAMANGA", "Azora Manga", "ar")
internal class Azora(context: MangaLoaderContext) :
    IkenParser(context, MangaParserSource.AZORAMANGA, "azorafly.com", 24)
