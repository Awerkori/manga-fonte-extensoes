package eu.kanade.tachiyomi.extension.pt.cerisescans

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.head
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@Source
abstract class CeriseScan : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(4, interval = 250.milliseconds)

    override suspend fun getPopularManga(page: Int): MangasPage = getComicList(page, sort = "views")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getComicList(page, sort = "recent")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getComicList(page, query = query, filters = filters)

    private suspend fun getComicList(
        page: Int,
        sort: String? = null,
        query: String = "",
        filters: FilterList = FilterList(),
    ): MangasPage {
        val url = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())

        if (sort != null) {
            url.addQueryParameter("sort", sort)
        }

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        filters.filterIsInstance<SelectFilter>().forEach { filter ->
            filter.selected?.let { url.addQueryParameter(filter.parameter, it) }
        }

        return client.get(url.build()).parseAs<ComicListDto>().toMangasPage(page, baseUrl)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val fragment = url.fragment ?: return null
        val slugOrId = MANGA_FRAGMENT_REGEX.matchEntire(fragment)?.groupValues?.get(1) ?: return null
        return getComic(slugOrId).toSManga(baseUrl)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val comic = getComic(manga.url.toSlug())
        val chapters = comic.lastChapters
            .sortedWith(compareByDescending<ChapterDto> { it.chapterNumber }.thenByDescending { it.timestamp })
            .map { it.toSChapter() }
        return SMangaUpdate(comic.toSManga(baseUrl), chapters)
    }

    private suspend fun getComic(slugOrId: String): ComicDto {
        val url = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addPathSegment(slugOrId)
            .build()
        return client.get(url).parseAs<ComicDto>()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.toChapterId()
        val pageCount = pageCount(chapterId)
        if (pageCount == 0) {
            throw IOException("Nenhuma imagem disponível para este capítulo (capítulos de texto/novel não são suportados).")
        }
        return (0 until pageCount).map { index ->
            Page(index, imageUrl = imageUrl(chapterId, index))
        }
    }

    // The site exposes pages anonymously as /api/read-image/{id}/{index} but never returns the count,
    // so probe the upper bound and then binary-search the first missing index.
    private suspend fun pageCount(chapterId: String): Int {
        if (!pageExists(chapterId, 0)) return 0

        var lastGood = 0
        var probe = 1
        while (probe < MAX_PAGES && pageExists(chapterId, probe)) {
            lastGood = probe
            probe *= 2
        }

        var low = lastGood + 1
        var high = minOf(probe, MAX_PAGES)
        while (low < high) {
            val mid = (low + high) / 2
            if (pageExists(chapterId, mid)) low = mid + 1 else high = mid
        }
        return low
    }

    private suspend fun pageExists(chapterId: String, index: Int): Boolean {
        var attempt = 0
        while (true) {
            client.head(imageUrl(chapterId, index), ensureSuccess = false).use { response ->
                when {
                    response.isSuccessful -> return true
                    response.code == 404 -> return false
                    response.code == 429 || response.code >= 500 -> Unit
                    else -> throw IOException("Falha ao verificar a página ${index + 1} do capítulo (HTTP ${response.code}).")
                }
            }
            if (++attempt >= 3) throw IOException("Servidor instável ao verificar as páginas do capítulo. Tente novamente.")
            delay(1000L * attempt)
        }
    }

    private fun imageUrl(chapterId: String, index: Int): String = "$baseUrl/api/read-image/$chapterId/$index"

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SelectFilter("Gênero", "genre", GENRES.map { it to it }),
        SelectFilter("Status", "status", STATUSES),
    )

    private class SelectFilter(
        name: String,
        val parameter: String,
        private val options: List<Pair<String, String>>,
    ) : Filter.Select<String>(name, arrayOf("Todos") + options.map { it.first }) {
        val selected: String? get() = if (state == 0) null else options[state - 1].second
    }

    override fun getHomeUrl(): String = "$baseUrl/#/library"

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/#/comic/${manga.url.toSlug()}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/#/read/${chapter.url}"

    // Library entries saved by the old Madara-based source have "/manga/<slug>/" as their URL.
    private fun String.toSlug(): String = substringAfter("/manga/", this)
        .removePrefix("manga/")
        .trim('/')
        .substringBefore('/')

    // Chapters saved by the old source carry a Madara-style URL instead of the LoversToon chapter id.
    private suspend fun String.toChapterId(): String {
        if (CHAPTER_ID_REGEX.matches(this)) return this

        val number = CHAPTER_NUMBER_REGEX.find(this)?.groupValues?.get(1) ?: return this
        return getComic(toSlug()).lastChapters
            .firstOrNull { it.matchesNumber(number) }
            ?.let { it.id }
            ?: this
    }

    companion object {
        private const val PAGE_SIZE = 50

        private const val MAX_PAGES = 400

        private val MANGA_FRAGMENT_REGEX = Regex("""/(?:comic|obra)/([^/?]+)""")

        private val CHAPTER_ID_REGEX = Regex("""^\d+_ch_.+$""")

        private val CHAPTER_NUMBER_REGEX = Regex("""(?:capitulo|capítulo|chapter)[-_]?(\d+(?:[.-]\d+)?)""", RegexOption.IGNORE_CASE)

        private val STATUSES = listOf(
            "Em Andamento" to "ongoing",
            "Finalizado" to "completed",
            "Hiato" to "hiatus",
            "Dropado" to "dropped",
            "Inativo" to "inactive",
            "Cancelado" to "cancelled",
        )

        private val GENRES = listOf(
            "Ação",
            "Adulto",
            "Apocalíptico",
            "Artes Marciais",
            "Aventura",
            "BL",
            "Comédia",
            "Drama",
            "Escolar",
            "Esportes",
            "Fantasia",
            "Ficção Científica",
            "Gore",
            "Harem",
            "Harém",
            "Histórico",
            "Horror",
            "Isekai",
            "Josei",
            "Magia",
            "Mistério",
            "Psicológico",
            "Reencarnação",
            "Regressão",
            "Romance",
            "Sci-fi",
            "Seinen",
            "Shoujo",
            "Shounen",
            "Slice of Life",
            "Smut",
            "Sobrenatural",
            "Suspense",
            "Tragédia",
            "Vida Escolar",
            "Vingança",
            "Webtoon",
            "Yaoi",
            "Yuri",
        )
    }
}
