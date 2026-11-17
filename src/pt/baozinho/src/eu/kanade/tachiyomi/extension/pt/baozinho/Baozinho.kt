package eu.kanade.tachiyomi.extension.pt.baozinho

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.booleanOrNull
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getLocalStorage
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.getTurnstileToken
import keiyoushi.utils.intOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Baozinho :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

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

    private val authMutex = Mutex()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val capId = chapter.memo["capId"]!!.string.toLong()
        val gratuito = chapter.memo["gratuito"]?.booleanOrNull ?: true
        val precoEmBaos = chapter.memo["precoEmBaos"]?.intOrNull ?: 0
        val chapterUrl = getChapterUrl(chapter)

        val token = getAccessToken()
        if (!gratuito && token == null) {
            throw IOException(lockedChapterMessage(precoEmBaos, loggedIn = false))
        }

        val session = openSession(capId, chapterUrl, token, precoEmBaos)

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

    private suspend fun openSession(
        capId: Long,
        chapterUrl: String,
        initialToken: String?,
        precoEmBaos: Int,
        allowRetryAuth: Boolean = true,
        allowUnlock: Boolean = true,
    ): SessionDto {
        val url = "$API_URL/leitor/sessao"
        val reqHeaders = authHeaders(initialToken)
        val initial = client.post(url, reqHeaders, Fetch.XHR, SessionRequest(capId).toJsonRequestBody(), ensureSuccess = false)
        if (initial.isSuccessful) return initial.parseAs()

        if (initial.code == 401 && allowRetryAuth) {
            initial.close()
            val refreshed = refreshOrLogin(forceRefresh = true)
                ?: throw IOException("Sessão expirada. Faça login novamente nas configurações da extensão ou pelo WebView.")
            return openSession(capId, chapterUrl, refreshed, precoEmBaos, allowRetryAuth = false, allowUnlock = allowUnlock)
        }

        val error = initial.readApiError()
        if (initial.code == 403 && error?.desafio == "turnstile") {
            val turnstileToken = getTurnstileToken(chapterUrl, TURNSTILE_SITE_KEY)
            val retry = client.post(
                url,
                reqHeaders,
                Fetch.XHR,
                SessionRequest(capId, turnstileToken = turnstileToken).toJsonRequestBody(),
                ensureSuccess = false,
            )
            if (retry.isSuccessful) return retry.parseAs()
            val retryError = retry.readApiError()
            return handleLockedOrError(retry.code, retryError, capId, chapterUrl, initialToken, precoEmBaos, allowUnlock)
        }

        return handleLockedOrError(initial.code, error, capId, chapterUrl, initialToken, precoEmBaos, allowUnlock)
    }

    private suspend fun handleLockedOrError(
        code: Int,
        error: ApiErrorDto?,
        capId: Long,
        chapterUrl: String,
        token: String?,
        precoEmBaos: Int,
        allowUnlock: Boolean,
    ): SessionDto {
        if (code == 403 || code == 402) {
            if (allowUnlock && token != null && precoEmBaos > 0 && preferences.getBoolean(PREF_AUTO_UNLOCK, false)) {
                unlockChapter(capId, token, precoEmBaos)
                return openSession(capId, chapterUrl, token, precoEmBaos, allowRetryAuth = false, allowUnlock = false)
            }
            throw IOException(lockedChapterMessage(precoEmBaos, loggedIn = token != null, serverMessage = error?.message))
        }
        throw IOException(error?.message ?: "HTTP error $code")
    }

    private suspend fun unlockChapter(capId: Long, token: String, precoEmBaos: Int) {
        val response = client.post(
            "$API_URL/pagamentos/capitulo/$capId/desbloquear",
            authHeaders(token),
            Fetch.XHR,
            "{}".toJsonRequestBody(),
            ensureSuccess = false,
        )
        if (response.isSuccessful) {
            response.close()
            return
        }
        val error = response.readApiError()
        throw IOException("Não foi possível desbloquear ($precoEmBaos baos): ${error?.message ?: "HTTP ${response.code}"}")
    }

    private suspend fun fetchBatch(sid: String, from: Int, chapterUrl: String): SessionDto {
        val url = "$API_URL/leitor/sessao/$sid/lote?de=$from"
        val token = getAccessToken()
        val reqHeaders = authHeaders(token)
        val initial = client.get(url, reqHeaders, Fetch.XHR, ensureSuccess = false)
        if (initial.isSuccessful) return initial.parseAs()
        val error = initial.readApiError()
        if (initial.code == 403 && error?.desafio == "turnstile") {
            val turnstileToken = getTurnstileToken(chapterUrl, TURNSTILE_SITE_KEY)
            val batchHeaders = reqHeaders.newBuilder().set("X-Turnstile", turnstileToken).build()
            return client.get(url, batchHeaders, Fetch.XHR).parseAs()
        }
        throw IOException(error?.message ?: "HTTP error ${initial.code}")
    }

    private fun Response.readApiError(): ApiErrorDto? = runCatching { parseAs<ApiErrorDto>() }.getOrNull()

    private fun authHeaders(token: String?): Headers = if (token.isNullOrBlank()) {
        headers
    } else {
        headers.newBuilder().set("Authorization", "Bearer $token").build()
    }

    private fun lockedChapterMessage(precoEmBaos: Int, loggedIn: Boolean, serverMessage: String? = null): String {
        val base = if (precoEmBaos > 0) {
            "Capítulo bloqueado (VIP ou $precoEmBaos baos)."
        } else {
            "Capítulo exclusivo para VIP."
        }
        val hint = when {
            !loggedIn -> "Faça login nas configurações da extensão ou pelo WebView."
            precoEmBaos > 0 -> "Ative o desbloqueio automático com moedas nas configurações da extensão ou desbloqueie no site."
            else -> "Verifique sua assinatura VIP no site."
        }
        return if (serverMessage.isNullOrBlank()) "$base $hint" else "$base $serverMessage ($hint)"
    }

    private suspend fun getAccessToken(): String? {
        preferences.getString(PREF_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return refreshOrLogin(forceRefresh = false)
    }

    private suspend fun refreshOrLogin(forceRefresh: Boolean): String? = authMutex.withLock {
        if (!forceRefresh) {
            preferences.getString(PREF_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val refreshToken = preferences.getString(PREF_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
        if (refreshToken != null) {
            val refreshed = runCatching {
                client.post(
                    "$API_URL/auth/refresh",
                    Fetch.XHR,
                    RefreshRequest(refreshToken).toJsonRequestBody(),
                ).parseAs<AuthResponseDto>()
            }.getOrNull()
            if (refreshed != null) {
                saveTokens(refreshed.accessToken, refreshed.refreshToken)
                return refreshed.accessToken
            }
        }

        // Sync session from WebView localStorage (supports Google OAuth and site login).
        if (!forceRefresh) {
            val webAccess = runCatching { getLocalStorage(baseUrl, "bz_access") }.getOrNull()?.takeIf { it.isNotBlank() }
            val webRefresh = runCatching { getLocalStorage(baseUrl, "bz_refresh") }.getOrNull()?.takeIf { it.isNotBlank() }
            if (webAccess != null) {
                saveTokens(webAccess, webRefresh)
                return webAccess
            }
        }

        val email = preferences.getString(PREF_EMAIL, null)?.trim().orEmpty()
        val password = preferences.getString(PREF_PASSWORD, null).orEmpty()
        if (email.isNotEmpty() && password.isNotEmpty()) {
            val turnstileToken = getTurnstileToken(baseUrl, TURNSTILE_SITE_KEY)
            val auth = client.post(
                "$API_URL/auth/login",
                Fetch.XHR,
                LoginRequest(email, password, turnstileToken).toJsonRequestBody(),
            ).parseAs<AuthResponseDto>()
            saveTokens(auth.accessToken, auth.refreshToken)
            return auth.accessToken
        }

        clearTokens()
        return null
    }

    private fun saveTokens(access: String, refresh: String?) {
        preferences.edit().apply {
            putString(PREF_ACCESS_TOKEN, access)
            if (!refresh.isNullOrBlank()) putString(PREF_REFRESH_TOKEN, refresh)
        }.apply()
    }

    private fun clearTokens() {
        preferences.edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
            .apply()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "E-mail"
            summary = preferences.getString(PREF_EMAIL, null)?.takeIf { it.isNotBlank() }
                ?: "Opcional (ou faça login pelo WebView, inclusive com Google)"
            dialogTitle = "E-mail da conta Baozinho"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                summary = value.ifEmpty { "Opcional (ou faça login pelo WebView, inclusive com Google)" }
                clearTokens()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "Senha"
            summary = if (preferences.getString(PREF_PASSWORD, null).isNullOrEmpty()) "Não definida" else "••••••••"
            dialogTitle = "Senha da conta Baozinho"
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as String).isEmpty()) "Não definida" else "••••••••"
                clearTokens()
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTO_UNLOCK
            title = "Desbloquear capítulos pagos automaticamente"
            summary = "Gasta moedas (baos) automaticamente ao abrir capítulos pagos quando você estiver logado."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    companion object {
        private const val API_URL = "https://api.baozinho.com"
        private const val TURNSTILE_SITE_KEY = "0x4AAAAAADtmuUfMFzDWFmF8"

        private const val PREF_EMAIL = "baozinho_email"
        private const val PREF_PASSWORD = "baozinho_password"
        private const val PREF_AUTO_UNLOCK = "baozinho_auto_unlock"
        private const val PREF_ACCESS_TOKEN = "baozinho_access_token"
        private const val PREF_REFRESH_TOKEN = "baozinho_refresh_token"
    }
}
