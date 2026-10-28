package tsuki.site.es.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.VerComicsParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("VERMANGASPORNO", "VerMangasPorno", "es", ContentType.HENTAI)
internal class VerMangasPorno(context: MangaLoaderContext) :
    VerComicsParser(context, MangaParserSource.VERMANGASPORNO, "vermangasporno.com") {

    override val urlSuffix   = "manga-hentai-xxx"
    override val genreSuffix = "tags"
    override val detailsSelector = "article.pp-single"
    override val genreSelector   = "div.pp-taglist a.pp-tag"
    override val pageListSelector = "div.pp-comic-content img"

    override val genres = arrayOf(
        "Ver todos" to "",
        "Ahegao" to "ahegao-xxx",
        "Anal" to "anal",
        "Big Ass" to "big-ass-xxx",
        "Big Breasts" to "big-breasts-xxx",
        "Big Penis" to "big-penis-xxx",
        "BlowJob" to "blowjob-xxx",
        "Creampie" to "creampie-xxx",
        "Cum" to "cum-xxx",
        "Hairy" to "hairy-xxx",
        "Incest" to "incest",
        "Manga Hentai" to "xxx-manga-hentai",
        "Milf" to "milfs-xxx-xxx",
        "Mosaic Censorship" to "mosaic-censorship-xxx",
        "Nakadashi" to "nakadashi-xxx",
        "Paizuri" to "paizuri",
        "Schoolgirl Uniform" to "schoolgirl-uniform",
        "Sin Censura" to "sin-censura-xxx",
        "Squirting" to "squirting-xxx",
        "Student" to "student-xxx",
        "Unusual Pupils" to "unusual-pupils-xxx",
    )
}
