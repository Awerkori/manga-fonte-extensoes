package eu.kanade.tachiyomi.extension.pt.argoscomics

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import eu.kanade.tachiyomi.network.HttpException
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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ArgosComics :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()
    private val authExecutor = Executors.newSingleThreadExecutor()
    private val email get() = preferences.getString(EMAIL_PREF, "")!!
    private val password get() = preferences.getString(PASSWORD_PREF, "")!!
    private val actionIds = ConcurrentHashMap(DEFAULT_ACTION_IDS)

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3, 2.seconds)
    }

    private val rscHeaders
        get() = headersBuilder().set("rsc", "1").build()

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        lateinit var status: androidx.preference.Preference
        lateinit var logout: androidx.preference.Preference

        fun update(valid: Boolean) {
            status.summary = if (valid) "Conectado" else "Não conectado"
            setPreferenceVisible(logout, valid)
        }

        status = createPreference(screen.context).apply {
            key = "argos_comics_status"
            title = "Sessão da Argos Comics"
            setPreferenceSelectable(this, false)
        }
        logout = createPreference(screen.context).apply {
            key = "argos_comics_logout"
            title = "Sair da conta"
            summary = "Remove a sessão da Argos Comics salva neste aplicativo."
            setOnPreferenceClickListener {
                AlertDialog.Builder(screen.context).setTitle("Sair da conta?")
                    .setMessage("Isso removerá a sessão da Argos Comics e o e-mail e a senha salvos.")
                    .setPositiveButton("Sair") { _, _ ->
                        clearSession()
                        preferences.edit().remove(EMAIL_PREF).remove(PASSWORD_PREF).apply()
                        update(false)
                    }
                    .setNegativeButton("Cancelar", null).show()
                true
            }
        }
        screen.addPreference(status)
        screen.addPreference(logout)
        EditTextPreference(screen.context).apply {
            key = EMAIL_PREF
            title = "E-mail"
            summary = "E-mail da conta da Argos Comics, para entrar sem abrir o site"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                clearSession()
                true
            }
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Senha"
            summary = "Senha da conta da Argos Comics"
            setDefaultValue("")
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, _ ->
                clearSession()
                true
            }
        }.also(screen::addPreference)
        update(false)
        authExecutor.execute {
            val valid = clientHasAccessToken()
            Handler(Looper.getMainLooper()).post { update(valid) }
        }
    }

    private fun createPreference(context: android.content.Context): Preference = runCatching {
        Preference::class.java.getConstructor(android.content.Context::class.java).newInstance(context)
    }.getOrElse { Preference() }

    private fun setPreferenceVisible(preference: Preference, visible: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { it.name == "setVisible" }?.invoke(preference, visible)
        }
    }

    private fun setPreferenceSelectable(preference: Preference, selectable: Boolean) {
        runCatching {
            preference::class.java.methods.firstOrNull { it.name == "setSelectable" }?.invoke(preference, selectable)
        }
    }

    private fun clientCookieNames(): List<String> = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        .map { it.name }.distinct().sorted()

    private fun clientHasAccessToken(): Boolean = clientCookieNames().contains("access_token")

    private fun clearSession() {
        val url = baseUrl.toHttpUrl()
        client.cookieJar.loadForRequest(url).forEach { cookie ->
            val expired = Cookie.Builder().name(cookie.name).value("").domain(cookie.domain).path(cookie.path)
                .expiresAt(1L).build()
            client.cookieJar.saveFromResponse(url, listOf(expired))
        }
    }

    private fun debugResponse(stage: String, response: okhttp3.Response) {
        val redirects = generateSequence(response.priorResponse) { it.priorResponse }.count()
        val clientCookies = client.cookieJar.loadForRequest(response.request.url).map { it.name }.distinct().sorted()
        Log.d(
            DEBUG_TAG,
            "stage=$stage url=${response.request.url} status=${response.code} " +
                "redirects=$redirects clientCookieNames=$clientCookies",
        )
    }

    // ======================== Popular =============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("projetos")
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url, rscHeaders, Fetch.DOCUMENT).extractNextJs<MangasListDto>()!!.toMangasPage()
    }

    // ======================== Latest =============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get(baseUrl, rscHeaders, Fetch.DOCUMENT).extractNextJs<LatestMangas>()!!.toMangasPage()

    // ======================== Search =============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val payload = listOf(query).toJsonRequestBody()
        val dto = callAction("search", baseUrl, payload, "$baseUrl/projetos").extractNextJs<List<MangaDto>>() ?: emptyList()
        return MangasPage(dto.map(MangaDto::toSManga), false)
    }

    // ======================== Details + Chapters =============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async {
            if (fetchDetails) {
                val url = getMangaUrl(manga)
                val payload = url.toHttpUrl().pathSegments.toJsonRequestBody()
                callAction("getOne", url, payload).also { debugResponse("DETAILS", it) }
                    .extractNextJs<MangaDetailsDto>()!!.toSManga()
            } else {
                manga
            }
        }

        val chaptersDeferred = async {
            if (fetchChapters) {
                val url = getMangaUrl(manga)
                val payload = url.toHttpUrl().pathSegments.toJsonRequestBody()
                val response = callAction("getAllChapters", url, payload)
                debugResponse("CHAPTERS", response)
                val pathSegment = url.substringAfter(baseUrl)
                response.extractNextJs<VolumeChapterDto>()!!.toChapterList(pathSegment)
            } else {
                chapters
            }
        }

        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    // ======================== Pages =============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (!ensureSession()) return emptyList()
        readPages(chapter)?.let { return it }
        clearSession()
        if (!login()) return emptyList()
        return readPages(chapter).orEmpty()
    }

    // null when the site did not accept the session
    private suspend fun readPages(chapter: SChapter): List<Page>? {
        val storedChapterUrl = chapter.url
        val url = normalizeChapterUrl(getChapterUrl(chapter))
        val segments = url.toHttpUrl().pathSegments
        val payload = listOf(segments.first(), segments.last()).toJsonRequestBody()
        Log.d(DEBUG_TAG, "stage=PAGES_CURRENT_ACTION_REQUEST url=$url arguments=project:${segments.first()},chapter:${segments.last()}")
        val response = callAction("getPages", url, payload)
        return response.use {
            val body = it.body.string()
            Log.d(DEBUG_TAG, "stage=PAGES_CURRENT_ACTION_RESPONSE status=${it.code} contentType=${it.header("Content-Type")} bodyLength=${body.length} containsSupabase=${body.contains("supabase.aniargos.com", true)} containsPages=${body.contains("pages", true)} containsPhoto=${body.contains("photo", true)}")
            val contentType = it.header("Content-Type").orEmpty()
            if (contentType.startsWith("application/json", ignoreCase = true) &&
                body.trim().let { value -> value.isEmpty() || value == "[]" || value == "{}" || value == "null" }
            ) {
                return null
            }
            runCatching { body.extractNextJsRsc<PagesDto>()?.toPageList() }
                .getOrNull()
                ?.also { pages -> Log.d(DEBUG_TAG, "stage=PAGES_CURRENT_ACTION_RESPONSE pages=${pages.size}") }
                .orEmpty()
        }
    }

    // ======================== Server Actions =============================

    private suspend fun callAction(name: String, pageUrl: String, payload: RequestBody, discoverFrom: String = pageUrl): Response {
        val url = pageUrl.toHttpUrl()
        var response = postAction(name, url, payload)
        if (response.code == 404 && isStaleAction(404, response.use { it.body.string() })) {
            actionIds[name] = discoverActionId(name, discoverFrom) ?: throw HttpException(404)
            response = postAction(name, url, payload)
        }
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
        return response
    }

    private suspend fun postAction(name: String, url: HttpUrl, payload: RequestBody): Response {
        val actionHeaders = headersBuilder()
            .set("Next-Action", actionIds.getValue(name))
            .build()
        return client.post(url, actionHeaders, Fetch.XHR.from(url.toString()), payload, ensureSuccess = false)
    }

    private suspend fun discoverActionId(name: String, pageUrl: String): String? {
        val html = client.get(pageUrl, headers, Fetch.DOCUMENT).use { it.body.string() }
        for (chunk in chunkNames(html)) {
            val js = client.get("$baseUrl/_next/static/chunks/$chunk", headers, Fetch.SCRIPT).use { it.body.string() }
            actionIdIn(js, name)?.let { return it }
        }
        return null
    }

    // ======================== Login =============================

    private suspend fun ensureSession() = clientHasAccessToken() || login()

    // false when there is no e-mail and password to log in with
    private suspend fun login(): Boolean {
        if (email.isBlank() || password.isBlank()) return false
        val body = callAction("login", "$baseUrl/login", listOf(email, password).toJsonRequestBody()).use { it.body.string() }
        if (!loggedIn(body)) throw HttpException(401)
        return true
    }

    private fun normalizeChapterUrl(url: String): String {
        val parsed = url.toHttpUrl()
        val segments = parsed.pathSegments
        val chapterIndex = segments.indexOfLast { it.equals("capitulo", ignoreCase = true) }
        if (chapterIndex < 0 || chapterIndex == segments.lastIndex) return url
        val number = segments.last()
        val normalizedNumber = number.removeSuffix(".0")
        if (number == normalizedNumber) return url
        return parsed.newBuilder()
            .removePathSegment(segments.lastIndex)
            .addPathSegment(normalizedNumber)
            .build()
            .toString()
    }

    companion object {
        private const val DEBUG_TAG = "ARGOS_COMICS_DEBUG"
        private const val EMAIL_PREF = "email"
        private const val PASSWORD_PREF = "password"
    }
}
