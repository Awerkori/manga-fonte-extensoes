package eu.kanade.tachiyomi.extension.pt.baozinho

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getTurnstileToken
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Baozinho : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(permits = 3, period = 1.seconds).addInterceptor(ScrambleInterceptor())

    // The site lists all works on a single page, so `page` is ignored and there is never a next page.
    override suspend fun getPopularManga(page: Int): MangasPage = parseMangasPage(client.get("$baseUrl/busca?orderBy=populares_total", Fetch.DOCUMENT))

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/atualizacoes", Fetch.DOCUMENT).asJsoup()
        val seen = mutableSetOf<String>()
        val mangas = document.select("a.atualizacoes-item").mapNotNull { element ->
            val slug = element.attr("href").removePrefix("/obras/").substringBefore('/')
            if (!seen.add(slug)) return@mapNotNull null
            SManga.create().apply {
                url = slug
                title = element.selectFirst(".atualizacoes-obra")!!.text()
                thumbnail_url = element.selectFirst(".atualizacoes-capa-img")?.absUrl("src")
            }
        }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/busca".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        return parseMangasPage(client.get(url, Fetch.DOCUMENT))
    }

    private fun parseMangasPage(response: Response): MangasPage {
        val mangas = response.asJsoup().select("a.portrait-card").map { element ->
            SManga.create().apply {
                url = element.attr("href").removePrefix("/obras/")
                title = element.selectFirst(".portrait-card-titulo")!!.text()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }
        return MangasPage(mangas, false)
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/obras/${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/obras/${chapter.memo["slug"]!!.string}/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "obras") return null
        return parseManga(client.get("$baseUrl/obras/${segments[1]}", Fetch.DOCUMENT).asJsoup(), segments[1])
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and chapters come from the same page, so both are always parsed from it.
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val updated = parseManga(document, manga.url)
        return SMangaUpdate(updated, parseChapters(document, manga.url))
    }

    private fun parseManga(document: Document, slug: String) = SManga.create().apply {
        url = slug
        title = document.selectFirst("h1.obra-topo-titulo")!!.text()
        thumbnail_url = document.selectFirst(".obra-capa-ficha img")?.absUrl("src")
        description = document.selectFirst(".obra-sinopse-inline")?.text()
        genre = document.select("li.obra-topo-tag").joinToString { it.text() }

        val fields = document.select(".obra-ficha-linha").associate {
            it.selectFirst(".obra-ficha-rotulo")!!.text() to it.selectFirst(".obra-ficha-valor")!!.text()
        }
        author = fields["Estúdio"]
        status = when (fields["Status"]) {
            "Em lançamento", "Em andamento" -> SManga.ONGOING
            "Concluído", "Completo" -> SManga.COMPLETED
            "Hiato", "Pausado" -> SManga.ON_HIATUS
            "Cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapters(document: Document, slug: String): List<SChapter> = document.extractNextJs<List<ChapterDto>>().orEmpty().map { it.toSChapter(slug) }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val capId = chapter.memo["capId"]!!.string.toLong()
        val chapterUrl = getChapterUrl(chapter)
        val session = openSession(capId, chapterUrl)

        val pages = session.paginas.toMutableList()
        var next = session.proximo
        while (next < session.total) {
            delay(session.intervaloMs.milliseconds)
            val batch = fetchBatch(session.sid, next, chapterUrl)
            if (batch.paginas.isEmpty()) break
            pages += batch.paginas
            next = batch.proximo
        }

        return pages.sortedBy { it.ordem }.mapIndexed { index, page ->
            val url = "$API_URL${page.url}"
            val seed = page.semente
            Page(index, imageUrl = if (seed.isNullOrBlank()) url else "$url#s=$seed")
        }
    }

    private suspend fun openSession(capId: Long, chapterUrl: String): SessionDto {
        val url = "$API_URL/leitor/sessao"
        val initial = client.post(url, Fetch.XHR, SessionRequest(capId).toJsonRequestBody(), ensureSuccess = false)
        if (initial.isSuccessful) return initial.parseAs()
        if (!initial.isTurnstileChallenge()) {
            val code = initial.code
            initial.close()
            throw IOException("HTTP error $code")
        }
        val token = getTurnstileToken(chapterUrl, TURNSTILE_SITE_KEY)
        return client.post(url, Fetch.XHR, SessionRequest(capId, turnstileToken = token).toJsonRequestBody())
            .parseAs()
    }

    private suspend fun fetchBatch(sid: String, from: Int, chapterUrl: String): SessionDto {
        val url = "$API_URL/leitor/sessao/$sid/lote?de=$from"
        val initial = client.get(url, Fetch.XHR, ensureSuccess = false)
        if (initial.isSuccessful) return initial.parseAs()
        if (!initial.isTurnstileChallenge()) {
            val code = initial.code
            initial.close()
            throw IOException("HTTP error $code")
        }
        val token = getTurnstileToken(chapterUrl, TURNSTILE_SITE_KEY)
        val batchHeaders = headers.newBuilder().set("X-Turnstile", token).build()
        return client.get(url, batchHeaders, Fetch.XHR).parseAs()
    }

    private fun Response.isTurnstileChallenge(): Boolean = code == 403 && runCatching { parseAs<ChallengeDto>().desafio == "turnstile" }.getOrDefault(false)

    companion object {
        private const val API_URL = "https://api.baozinho.com"
        private const val TURNSTILE_SITE_KEY = "0x4AAAAAADtmuUfMFzDWFmF8"
    }
}
