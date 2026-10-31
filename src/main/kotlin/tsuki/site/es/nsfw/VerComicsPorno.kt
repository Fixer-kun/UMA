package tsuki.site.es.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.VerComicsParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("VERCOMICSPORNO", "VerComicsPorno", "es", ContentType.HENTAI)
internal class VerComicsPorno(context: MangaLoaderContext) :
    VerComicsParser(context,MangaParserSource.VERCOMICSPORNO, "vercomicsporno.com") {

    override val urlSuffix   = "sex-comics"
    override val genreSuffix = "tags"
    override val detailsSelector = "article.pp-single"
    override val genreSelector   = "div.pp-taglist a.pp-tag"
    override val pageListSelector = "div.pp-comic-content img"

    override val genres = arrayOf(
        "Ver todos" to "",
        "Anal" to "anal",
        "Big Ass" to "big-ass-xxx",
        "Big Breasts" to "big-breasts-xxx",
        "Big Cock" to "big-cock",
        "Big Penis" to "big-penis",
        "Big Tits" to "big-tits",
        "Blowjob" to "blowjob",
        "Culonas" to "culonas-xxx",
        "Cum" to "cum",
        "Dark Skin" to "dark-skin",
        "Furry" to "furry-xxx",
        "Hot Girls" to "hot-girls-xxx",
        "Incest" to "incest",
        "Mamadas" to "mamadas",
        "Muscle" to "muscle",
        "Nakadashi" to "nakadashi-xxx",
        "Sole Female" to "sole-female-xxx",
        "Sole Male" to "sole-male",
        "Tetonas" to "tetonas-xxx",
    )
}
