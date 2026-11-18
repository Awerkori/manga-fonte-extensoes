package eu.kanade.tachiyomi.extension.pt.reinomanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.MarkdownDescription
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.selectLast
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Source
abstract class ReinoManga : KeiSource() {

    private val host get() = baseUrl.toHttpUrl().host

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("featured")))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("updated-desc")))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/catalogo/".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            }
            filters.filterIsInstance<QueryFilter>().forEach { it.applyTo(this) }
            if (page > 1) {
                addQueryParameter("pagina", page.toString())
            }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = document.select("[data-catalogo-grade] > a[href^=/manga/]").map { it.toSManga() }
        val hasNextPage = document.selectFirst("nav[aria-label=Paginação] a[rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga() = SManga.create().apply {
        setUrlWithoutDomain(absUrl("href"))
        title = selectFirst("h3")!!.text()
        thumbnail_url = selectFirst("img")?.absUrl("src")
    }

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host.removePrefix("www.") != host) return super.getMangasByUrl(url, page)
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        return when (segments.firstOrNull()) {
            "catalogo" -> {
                val query = url.queryParameter("q").orEmpty()
                val filters = FilterList(
                    buildList {
                        url.queryParameter("ordem")?.let { add(SortFilter(it)) }
                        url.queryParameter("status")?.let { add(StatusFilter(it)) }
                        url.queryParameter("genero")?.let { slug ->
                            add(GenreFilter(mapOf(slug to slug), slug))
                        }
                    },
                )
                getSearchMangaList(page, query, filters)
            }
            "genero" -> {
                val slug = segments.getOrNull(1) ?: return MangasPage(emptyList(), false)
                getSearchMangaList(page, "", FilterList(GenreFilter(mapOf(slug to slug), slug)))
            }
            "scans" -> {
                val slug = segments.getOrNull(1) ?: return MangasPage(emptyList(), false)
                val document = client.get("$baseUrl/scans/$slug/", Fetch.DOCUMENT).asJsoup()
                val mangas = document.select(".scn__obras > a[href^=/manga/]").map { it.toSManga() }
                MangasPage(mangas, false)
            }
            else -> super.getMangasByUrl(url, page)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != host) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.firstOrNull() != "manga" || segments.size < 2) return null
        val mangaUrl = "/manga/${segments[1]}/"
        val document = client.get("$baseUrl$mangaUrl", Fetch.DOCUMENT).asJsoup()
        return document.toSManga(mangaUrl).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(document.toSManga(manga.url), document.toSChapters())
    }

    private fun Document.toSManga(mangaUrl: String) = SManga.create().apply {
        url = mangaUrl
        title = selectFirst("[data-abas] > h1")!!.text()
        thumbnail_url = selectFirst("aside img")?.absUrl("src")
        author = selectFirst("#painel-detalhes dt:containsOwn(Autor) + dd")?.textOrNull()
        artist = selectFirst("#painel-detalhes dt:containsOwn(Artista) + dd")?.textOrNull()
        genre = select("#painel-detalhes a[href^=/genero/]")
            .mapNotNull { it.textOrNull() }
            .distinct()
            .joinToString()
            .ifEmpty { null }
        status = when (selectLast("aside .flex-wrap > span")?.text()?.lowercase()) {
            "em andamento" -> SManga.ONGOING
            "completo" -> SManga.COMPLETED
            "hiato" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        description = MarkdownDescription.Builder()
            .info(
                selectFirst("#painel-detalhes dt:containsOwn(Ano) + dd")?.textOrNull(),
                selectFirst("aside span.bg-sakura-hot")?.textOrNull(),
            )
            .description(selectFirst("#painel-detalhes h2:containsOwn(Sinopse) + p"), keepNewlines = true)
            .altTitles(
                selectFirst("#painel-detalhes h2:containsOwn(Títulos alternativos) + p")
                    ?.textOrNull()
                    ?.split(",")
                    .orEmpty(),
                "Títulos alternativos",
            )
            .build()
    }

    // Chapters are listed oldest first on the page
    private fun Document.toSChapters(): List<SChapter> {
        val scanlator = select(".cs-credito .cs-scan .cs-texto strong")
            .eachText()
            .joinToString()
            .ifEmpty { null }

        return select("#painel-capitulos ul > li").map { item ->
            val link = item.selectFirst("a[href]")!!
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.selectFirst("span.truncate")!!.text()
                date_upload = Instant.tryParse(link.selectFirst("time[datetime]")?.attr("datetime"))
                this.scanlator = scanlator
            }
        }.reversed()
    }

    override val imageFetch = Fetch.IMAGE

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        .select("main img[src*=/chapters/]")
        .mapIndexed { index, img -> Page(index, imageUrl = img.absUrl("src")) }

    override val supportsRelatedMangas: Boolean = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        .select("section:has(h2:containsOwn(Continue no mesmo clima)) a[href^=/manga/]")
        .map { it.toSManga() }

    override val supportsFilterFetching: Boolean = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/generos/", Fetch.DOCUMENT).asJsoup()
        return document.select("a[href^=/genero/]").associate { element ->
            val name = element.selectFirst("h2")!!.text()
            val slug = element.attr("href").removeSurrounding("/genero/", "/")
            name to slug
        }.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<Map<String, String>>().orEmpty()
        return getFilters(genres)
    }
}
