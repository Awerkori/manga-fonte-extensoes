package eu.kanade.tachiyomi.extension.pt.fliptru

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class Fliptru : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get("$baseUrl/most_popular_list/").asJsoup()
        return MangasPage(parseComicCards(document), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getBrowsePage("$baseUrl/comics/all", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            if (page > 1) return MangasPage(emptyList(), false)
            return searchComics(query)
        }
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty()
        val url = if (genre.isNotEmpty()) "$baseUrl/category/$genre" else "$baseUrl/comics/all"
        return getBrowsePage(url, page)
    }

    private suspend fun getBrowsePage(base: String, page: Int): MangasPage {
        val url = base.toHttpUrl().newBuilder()
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        val document = client.get(url).asJsoup()
        val hasNextPage = document.selectFirst("#loadMoreComics a") != null
        return MangasPage(parseComicCards(document), hasNextPage)
    }

    private suspend fun searchComics(query: String): MangasPage {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .build()
        val mangas = client.get(url).parseAs<List<SearchResultDto>>()
            .mapNotNull { result ->
                val path = result.url.substringBefore("?").removeSuffix("/info")
                if (!path.startsWith("/comic/")) return@mapNotNull null
                val title = result.label.substringBefore(" - @").ifBlank { result.label }
                if (title.isBlank()) return@mapNotNull null
                SManga.create().apply {
                    this.url = path
                    this.title = title
                }
            }
        return MangasPage(mangas, false)
    }

    private val thumbnailRegex = Regex("""url\((https?://[^)]+)\)""")

    private fun parseComicCards(document: Document): List<SManga> = document.select("a.comic-card[href^=\"/comic/\"]")
        .mapNotNull { card ->
            val url = card.attr("href").substringBefore("?")
            val title = card.attr("data-title")
                .ifBlank { card.selectFirst("h3")?.text().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = thumbnailRegex.find(card.attr("style"))?.groupValues?.get(1)
            }
        }
        .distinctBy { it.url }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(GenreFilter())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "comic") return null
        return SManga.create().apply {
            this.url = "/comic/${segments[1]}"
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga.url),
            chapters = fetchAllChapters(document) { client.get(baseUrl + it).asJsoup() }.map {
                SChapter.create().apply {
                    url = it.path
                    name = it.name
                    chapter_number = it.number
                }
            },
        )
    }

    private fun parseMangaDetails(document: Document, mangaUrl: String): SManga {
        val ld = document.select("script[type=\"application/ld+json\"]")
            .mapNotNull { runCatching { it.data().parseAs<ComicSeriesLd>() }.getOrNull() }
            .firstOrNull { it.type == "ComicSeries" }

        val title = ld?.name?.ifBlank { null }
            ?: document.selectFirst("h1")!!.text()

        return SManga.create().apply {
            url = mangaUrl
            this.title = title
            thumbnail_url = ld?.image?.ifBlank { null }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            description = buildList {
                val desc = ld?.description?.ifBlank { null }
                    ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                if (!desc.isNullOrBlank()) add(desc.trim())
                ld?.genre?.takeIf { it.isNotBlank() }?.let { add("Gênero: $it") }
                ld?.author?.name?.takeIf { it.isNotBlank() }?.let { add("Autor: @$it") }
            }.joinToString("\n\n").ifEmpty { null }
            author = ld?.author?.name?.ifBlank { null }
            genre = ld?.genre?.ifBlank { null }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url)
        // An age-restricted chapter sends the reader to the login page.
        if (response.request.url.encodedPath.startsWith("/users/")) {
            response.close()
            return emptyList()
        }
        val document = response.asJsoup()
        return parsePageUrls(document)
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
    }
}
