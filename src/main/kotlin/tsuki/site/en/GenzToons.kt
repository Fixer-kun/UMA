package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.KeyoApp

import tsuki.model.Manga
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

@MangaSourceParser("GENZTOONS", "Genz Toons", "en")
internal class GenzToons(context: MangaLoaderContext) :
    KeyoApp(context, MangaParserSource.GENZTOONS, "genztoons.org") {

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val effectiveOrder = if (order == SortOrder.POPULARITY) SortOrder.RELEVANCE else order
        return super.getListPage(page, effectiveOrder, filter)
    }
}
