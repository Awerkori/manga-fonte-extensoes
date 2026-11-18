package eu.kanade.tachiyomi.extension.pt.mangalivre

import android.os.Looper
import android.webkit.CookieManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.call
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.getTurnstileToken
import keiyoushi.utils.intOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaLivre :
    KeiSource(),
    ConfigurableSource {

    override val imageFetch: Fetch = Fetch.IMAGE

    private val apiUrl: String get() = "$baseUrl/api"

    private val preferences by getPreferencesLazy()

    private val useAlternativeTitle: Boolean
        get() = preferences.getBoolean(ALTERNATIVE_TITLE_PREF, false)

    private val readerCoordinator by lazy {
        ReaderCoordinator(
            clientProvider = { client },
            headersProvider = { headers },
            baseUrlProvider = { baseUrl },
            turnstileSolver = { readerUrl ->
                getTurnstileToken(
                    url = readerUrl,
                    siteKey = TURNSTILE_SITE_KEY,
                    action = TURNSTILE_ACTION,
                )
            },
        )
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        readerCoordinator.interceptImage(chain)
    }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (request.url.isProtectedEdgeImage()) {
                // Protected edge image endpoints enforce credentials: "omit" and referrerPolicy: "no-referrer".
                chain.proceed(
                    request.newBuilder()
                        .removeHeader("Cookie")
                        .removeHeader("Referer")
                        .build(),
                )
            } else {
                chain.proceed(request)
            }
        }
        .rateLimit(12, 1.seconds) { it.host == baseUrl.toHttpUrl().host }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set(
        "Accept-Language",
        "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSearchManga(
        page = page,
        query = "",
        sortBy = SORT_POPULAR,
        sortOrder = DIRECTION_DESC,
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSearchManga(
        page = page,
        query = "",
        sortBy = SORT_UPDATED,
        sortOrder = DIRECTION_DESC,
    )

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = fetchSearchManga(
        page = page,
        query = query,
        sortBy = filters.firstInstanceOrNull<OrderByFilter>()?.selected ?: SORT_POPULAR,
        sortOrder = filters.firstInstanceOrNull<OrderDirectionFilter>()?.selected ?: DIRECTION_DESC,
        types = filters.firstInstanceOrNull<TypeFilter>()?.state?.filter { it.state }?.map { it.value }.orEmpty(),
        statuses = filters.firstInstanceOrNull<StatusFilter>()?.state?.filter { it.state }?.map { it.value }.orEmpty(),
    )

    private suspend fun fetchSearchManga(
        page: Int,
        query: String,
        sortBy: String,
        sortOrder: String,
        types: List<String> = emptyList(),
        statuses: List<String> = emptyList(),
    ): MangasPage {
        val url = "$apiUrl/mangas/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", SEARCH_LIMIT)
            .addQueryParameter("sortBy", sortBy)
            .addQueryParameter("sortOrder", sortOrder)
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", query.trim())
                }
                types.forEach { addQueryParameter("type", it) }
                statuses.forEach { addQueryParameter("status", it) }
            }
            .build()

        val dto = client.get(url, Fetch.XHR).parseJson<WrapperDto>()
        return MangasPage(
            mangas = dto.mangas.map { it.toSManga(useAlternativeTitle) },
            hasNextPage = dto.hasNextPage,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.firstOrNull(String::isNotEmpty) ?: return null
        if (slug in NON_MANGA_PATHS) return null

        val endpoint = "$apiUrl/manga-by-slug".toHttpUrl().newBuilder()
            .addPathSegment(slug)
            .build()
        val response = client.get(endpoint, Fetch.XHR, ensureSuccess = false)
        if (response.code == 404) {
            response.close()
            return null
        }
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
        return response.parseJson<MangaDto>().toSManga(useAlternativeTitle)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url.trimStart('/')}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = fetchMangaDto(manga.url.trim('/'))
        return SMangaUpdate(
            manga = dto.toSManga(useAlternativeTitle),
            chapters = dto.toSChapterList(),
        )
    }

    private suspend fun fetchMangaDto(idOrSlug: String): MangaDto {
        // `/mangas/{id}/reader` omits per-user rating maps and duplicate recentChapters arrays (~58% smaller payload).
        if (idOrSlug.startsWith("obra-")) {
            val readerEndpoint = "$apiUrl/mangas".toHttpUrl().newBuilder()
                .addPathSegment(idOrSlug)
                .addPathSegment("reader")
                .build()
            val response = client.get(
                readerEndpoint,
                Fetch.XHR,
                cacheControl = CacheControl.FORCE_NETWORK,
                ensureSuccess = false,
            )
            if (response.isSuccessful) {
                return response.parseJson()
            }
            response.close()
            if (response.code != 404) {
                throw HttpException(response.code)
            }
        }

        val slugEndpoint = "$apiUrl/manga-by-slug".toHttpUrl().newBuilder()
            .addPathSegment(idOrSlug)
            .build()
        return client.get(
            slugEndpoint,
            Fetch.XHR,
            cacheControl = CacheControl.FORCE_NETWORK,
        ).parseJson()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url.substringBefore('#')}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val cleanPath = chapter.url.substringBefore('#')
        val chapterUrl = "$baseUrl${chapter.url}".toHttpUrl()
        val readerUrl = "$baseUrl$cleanPath"

        val ref = resolveChapterReference(chapter, chapterUrl) ?: return emptyList()
        return readerCoordinator.getPageList(
            mangaId = ref.mangaId,
            chapterId = ref.chapterId,
            readerUrl = readerUrl,
            fallbackPageCount = ref.pageCount,
        )
    }

    private suspend fun resolveChapterReference(
        chapter: SChapter,
        chapterUrl: HttpUrl,
    ): ChapterReferenceDto? {
        val memoMangaId = chapter.memo["mangaId"]?.stringOrNull
        val memoChapterId = chapter.memo["chapterId"]?.stringOrNull
        if (!memoMangaId.isNullOrEmpty() && !memoChapterId.isNullOrEmpty()) {
            return ChapterReferenceDto(
                mangaId = memoMangaId,
                chapterId = memoChapterId,
                pageCount = chapter.memo["pageCount"]?.intOrNull,
            )
        }

        chapterUrl.fragment?.let { fragment ->
            runCatching { fragment.parseAs<ChapterReferenceDto>() }
                .getOrNull()
                ?.takeIf { it.mangaId.isNotEmpty() && it.chapterId.isNotEmpty() }
                ?.let { return it }
        }

        val segments = chapterUrl.pathSegments.filter(String::isNotEmpty)
        if (segments.size < 2) return null
        val slug = segments.first()
        val chapterNumber = segments.last()
        val endpoint = "$apiUrl/manga-by-slug".toHttpUrl().newBuilder()
            .addPathSegment(slug)
            .build()
        val dto = client.get(endpoint, Fetch.XHR)
            .parseJson<MangaDto>()
        val matched = dto.chapters?.firstOrNull { it.number == chapterNumber } ?: return null
        return ChapterReferenceDto(
            mangaId = dto.id,
            chapterId = matched.id,
            pageCount = matched.pageCount,
        )
    }

    override suspend fun getImageUrl(page: Page): String = readerCoordinator.getImageUrl(page.url)

    override fun getImageRequest(page: Page): Request = readerCoordinator.buildImageRequest(
        imageUrl = page.imageUrl!!,
        baseHeaders = headers,
        baseUrl = baseUrl,
    )

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        OrderByFilter(
            "Ordem",
            arrayOf(
                "Mais Visualizados" to SORT_POPULAR,
                "Lançamentos" to SORT_RELEASE,
                "Última Atualização" to SORT_UPDATED,
                "Melhor Avaliação" to SORT_RATING,
                "A-Z" to SORT_TITLE,
            ),
        ),
        OrderDirectionFilter(
            "Direção",
            arrayOf(
                "↑ Decrescente" to DIRECTION_DESC,
                "↓ Crescente" to DIRECTION_ASC,
            ),
        ),
        Filter.Separator(),
        TypeFilter(
            listOf(
                CheckBoxOption("Mangá", "Mangá"),
                CheckBoxOption("Manhwa", "Manhwa"),
                CheckBoxOption("Manhua", "Manhua"),
                CheckBoxOption("Webtoon", "Webtoon"),
            ),
        ),
        StatusFilter(
            listOf(
                CheckBoxOption("Em Lançamento", "Ongoing"),
                CheckBoxOption("Completo", "Completed"),
                CheckBoxOption("Em Hiato", "Hiatus"),
                CheckBoxOption("Cancelado", "Canceled"),
            ),
        ),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = ALTERNATIVE_TITLE_PREF
            title = "Título alternativo"
            summary = buildString {
                append("Usar títulos alternativos como principal quando disponível.")
                append(" Essa opção não tem efeito sobre obras já adicionadas na sua biblioteca.")
            }
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        internal const val TURNSTILE_SITE_KEY = "0x4AAAAAAC_2UGhBWthpfExC"
        internal const val TURNSTILE_ACTION = "reader-chapter"
        internal const val READER_VERIFICATION_REQUIRED = "Reader verification required"
        internal const val NON_JSON_MESSAGE =
            "Resposta não-JSON (Cloudflare ou proteção ativa). Abra a fonte na WebView do app e tente de novo."

        private const val SEARCH_LIMIT = "48"
        private const val ALTERNATIVE_TITLE_PREF = "alternativeTitlePref"
        private const val SORT_POPULAR = "popular"
        private const val SORT_RELEASE = "release"
        private const val SORT_UPDATED = "updated"
        private const val SORT_RATING = "rating"
        private const val SORT_TITLE = "title"
        private const val DIRECTION_DESC = "desc"
        private const val DIRECTION_ASC = "asc"

        private val NON_MANGA_PATHS = setOf(
            "api",
            "assets",
            "vip",
            "favorites",
            "profile",
            "admin",
        )
    }
}

@Serializable
internal class PageTokenDto(
    val m: String,
    val c: String,
    val p: Int,
    val t: Int,
    val r: String? = null,
)

internal class ReaderCoordinator(
    private val clientProvider: () -> OkHttpClient,
    private val headersProvider: () -> Headers,
    private val baseUrlProvider: () -> String,
    private val turnstileSolver: suspend (readerUrl: String) -> String,
    private val transientRetryBaseDelayMs: Long = 350L,
    private val enableBackgroundTasks: Boolean = true,
    private val callExecutor: (suspend (OkHttpClient, Request, Boolean) -> Response)? = null,
) {
    private val client: OkHttpClient get() = clientProvider()
    private val headers: Headers get() = headersProvider()
    private val baseUrl: String get() = baseUrlProvider()
    private val apiUrl: String get() = "$baseUrl/api"

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verificationMutex = Mutex()
    private val verificationGeneration = AtomicInteger(0)

    private val chapterSessions = Collections.synchronizedMap(
        object : LinkedHashMap<String, ChapterSession>(MAX_CHAPTER_SESSIONS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChapterSession>?): Boolean = size > MAX_CHAPTER_SESSIONS
        },
    )

    private val pageCacheDir: File by lazy {
        val tmpDir: String = System.getProperty("java.io.tmpdir") ?: "/tmp"
        val root = runCatching { applicationContext.cacheDir }.getOrNull() ?: File(tmpDir)
        File(root, "toonlivre_pages").apply { mkdirs() }
    }

    internal fun isPageResolved(mangaId: String, chapterId: String, pageNumber: Int): Boolean = chapterSessions["$mangaId/$chapterId"]?.resolvedPages?.containsKey(pageNumber) == true

    private fun csrfToken(): String? = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        .firstOrNull { it.name == "csrf_token" }
        ?.value
        ?.takeIf(String::isNotEmpty)

    suspend fun getPageList(
        mangaId: String,
        chapterId: String,
        readerUrl: String,
        fallbackPageCount: Int?,
    ): List<Page> {
        val sessionKey = "$mangaId/$chapterId"
        val now = System.currentTimeMillis()
        val existing = chapterSessions[sessionKey]?.takeIf { it.isReusableForPageList(now) }
        val session = if (existing != null) {
            existing
        } else {
            val access = requestChapterAccess(mangaId, chapterId, readerUrl, forceVerify = false)
            val directPages = access.chapter?.pages?.mapNotNull(String::toValidImageUrl).orEmpty()
            if (directPages.isNotEmpty()) {
                return directPages.mapIndexed { index, imageUrl ->
                    Page(index, imageUrl = imageUrl)
                }
            }

            val totalPages = access.chapter?.pageCount
                ?: access.pageCount
                ?: access.firstHandle?.pageCount
                ?: fallbackPageCount
                ?: 0
            if (totalPages <= 0 || (!access.protectedPages && access.grant?.token.isNullOrEmpty())) {
                return emptyList()
            }

            chapterSessions.getOrPut(sessionKey) {
                ChapterSession(mangaId, chapterId, readerUrl, totalPages)
            }.apply {
                applyAccess(access, totalPages)
            }
        }

        if (session.totalPages <= 0) {
            return emptyList()
        }

        val firstToken = PageTokenDto(m = mangaId, c = chapterId, p = 1, t = session.totalPages)
        try {
            resolvePageImage(firstToken, readerUrl, forceRefresh = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall back to lazy getImageUrl(page) if Page 1 resolution fails transiently.
        }

        if (session.totalPages > 1) {
            schedulePrefetchAhead(
                session = session,
                readerUrl = readerUrl,
                fromPage = 2,
                upToPage = minOf(session.totalPages, INITIAL_PREFETCH_PAGES),
            )
        }

        return buildPageList(session, readerUrl, mangaId, chapterId)
    }

    private fun buildPageList(
        session: ChapterSession,
        readerUrl: String,
        mangaId: String,
        chapterId: String,
    ): List<Page> = (1..session.totalPages).map { pageNumber ->
        val token = PageTokenDto(m = mangaId, c = chapterId, p = pageNumber, t = session.totalPages)
        val pageUrl = "$readerUrl#$PAGE_FRAGMENT_PREFIX${token.toJsonString()}"
        val cachedImageUrl = session.resolvedPages[pageNumber]
            ?.let { formatResolvedImageUrl(it, token, readerUrl) }
        Page(
            index = pageNumber - 1,
            url = pageUrl,
            imageUrl = cachedImageUrl,
        )
    }

    private fun formatResolvedImageUrl(
        resolved: ResolvedPage,
        token: PageTokenDto,
        readerUrl: String,
    ): String {
        if (!resolved.protectedEdge) {
            return resolved.imageUrl
        }
        val edgeToken = PageTokenDto(
            m = token.m,
            c = token.c,
            p = token.p,
            t = token.t,
            r = readerUrl,
        )
        return "${resolved.imageUrl}#$PAGE_FRAGMENT_PREFIX${edgeToken.toJsonString()}"
    }

    suspend fun getImageUrl(pageUrl: String): String {
        val parsedUrl = pageUrl.toHttpUrl()
        val fragment = parsedUrl.fragment
            ?.takeIf { it.startsWith(PAGE_FRAGMENT_PREFIX) }
            ?.removePrefix(PAGE_FRAGMENT_PREFIX)
            ?: throw IOException("Metadados da página ausentes. Atualize o capítulo.")
        val token = fragment.parseAs<PageTokenDto>()
        val readerUrl = token.r ?: parsedUrl.newBuilder().fragment(null).build().toString()

        val resolved = resolvePageImage(token, readerUrl, forceRefresh = false)
        chapterSessions["${token.m}/${token.c}"]?.let { session ->
            if (session.protectedPages && token.p < session.totalPages) {
                schedulePrefetchAhead(
                    session = session,
                    readerUrl = readerUrl,
                    fromPage = token.p + 1,
                    upToPage = minOf(session.totalPages, token.p + SLIDING_PREFETCH_WINDOW),
                )
            }
        }
        return formatResolvedImageUrl(resolved, token, readerUrl)
    }

    private fun schedulePrefetchAhead(
        session: ChapterSession,
        readerUrl: String,
        fromPage: Int,
        upToPage: Int,
    ) {
        if (!enableBackgroundTasks || fromPage > upToPage) return
        session.prefetchTargetPage.updateAndGet { prev -> maxOf(prev, upToPage) }
        if (!session.prefetchInFlight.compareAndSet(false, true)) return

        backgroundScope.launch {
            try {
                if (session.protectedPages) {
                    while (isActive) {
                        val shouldContinue = session.mutex.withLock {
                            val handle = session.nextHandle ?: return@withLock false
                            val handlePage = handle.pageNumber ?: 1
                            if (handlePage > session.prefetchTargetPage.get()) {
                                return@withLock false
                            }
                            when (val step = fetchProtectedStep(session, handle, readerUrl)) {
                                is StepOutcome.Success -> {
                                    session.resolvedPages[step.pageNumber] = step.page
                                    session.nextHandle = step.nextHandle
                                    step.nextHandle != null && step.pageNumber < session.prefetchTargetPage.get()
                                }
                                StepOutcome.ExpiredHandle -> {
                                    session.nextHandle = null
                                    false
                                }
                                StepOutcome.VerificationRequired -> false
                            }
                        }
                        if (!shouldContinue) break
                    }
                } else {
                    coroutineScope {
                        (fromPage..session.prefetchTargetPage.get())
                            .filter { page -> !session.resolvedPages.containsKey(page) }
                            .map { page ->
                                async {
                                    try {
                                        resolveGrantPage(session, page, readerUrl, forceRefresh = false)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }
                            .awaitAll()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Background prefetch is opportunistic; foreground getImageUrl will retry cleanly.
            } finally {
                session.prefetchInFlight.set(false)
            }
        }
    }

    fun buildImageRequest(
        imageUrl: String,
        baseHeaders: Headers,
        baseUrl: String,
    ): Request {
        val url = imageUrl.toHttpUrl()
        if (url.isProtectedEdgeImage()) {
            val edgeHeaders = Headers.Builder().apply {
                baseHeaders["User-Agent"]?.let { set("User-Agent", it) }
                baseHeaders["Accept-Language"]?.let { set("Accept-Language", it) }
                set("Origin", baseUrl)
                set("Cache-Control", "no-store")
            }.build()
            return Request.Builder()
                .url(url)
                .headers(edgeHeaders)
                .fetch(Fetch.XHR)
                .build()
        }

        return Request.Builder()
            .url(url)
            .headers(baseHeaders)
            .build()
    }

    fun interceptImage(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.isLocalCachedPage(baseUrl.toHttpUrl().host)) {
            return serveCachedBinaryPage(chain, request)
        }

        if (!url.isProtectedEdgeImage()) {
            return chain.proceed(request)
        }

        val response = proceedWithEdgeRetry(chain, request)
        if (response.code != 403 && response.code != 404) {
            return response
        }

        val fragment = url.fragment
            ?.takeIf { it.startsWith(PAGE_FRAGMENT_PREFIX) }
            ?.removePrefix(PAGE_FRAGMENT_PREFIX)
            ?: return response

        val isMainThread = runCatching {
            val mainLooper = Looper.getMainLooper()
            mainLooper != null && Looper.myLooper() == mainLooper
        }.getOrDefault(false)
        if (isMainThread) return response

        response.close()
        val token = runCatching { fragment.parseAs<PageTokenDto>() }.getOrNull()
            ?: throw IOException("Falha ao atualizar token expirado da imagem.")
        val readerUrl = token.r ?: "$baseUrl/"
        val refreshed = runBlocking {
            resolvePageImage(token, readerUrl, forceRefresh = true)
        }
        val cleanUrl = refreshed.imageUrl.toHttpUrl().newBuilder().fragment(null).build()
        val retryRequest = buildImageRequest(cleanUrl.toString(), headers, baseUrl)
        return proceedWithEdgeRetry(chain, retryRequest)
    }

    private fun proceedWithEdgeRetry(chain: Interceptor.Chain, request: Request): Response {
        var lastError: IOException? = null
        for (attempt in 0..1) {
            try {
                val response = chain.proceed(request)
                if (attempt == 0 && response.code >= 500) {
                    response.close()
                    continue
                }
                return response
            } catch (e: IOException) {
                lastError = e
                if (attempt == 1) throw e
            }
        }
        throw lastError ?: IOException("Imagem protegida indisponível.")
    }

    private fun serveCachedBinaryPage(chain: Interceptor.Chain, request: Request): Response {
        val url = request.url
        val fileName = url.pathSegments.lastOrNull().orEmpty()
        val file = File(pageCacheDir, fileName)
        if (file.exists() && file.length() > 0L) {
            val mime = url.queryParameter("mime") ?: "image/webp"
            val body = file.source().buffer().asResponseBody(mime.toMediaTypeOrNull(), file.length())
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }

        val fragment = url.fragment
            ?.takeIf { it.startsWith(PAGE_FRAGMENT_PREFIX) }
            ?.removePrefix(PAGE_FRAGMENT_PREFIX)
            ?: throw IOException("Cache temporário da página expirou.")
        val token = fragment.parseAs<PageTokenDto>()
        val readerUrl = token.r ?: "$baseUrl/"
        val refreshed = runBlocking {
            resolvePageImage(token, readerUrl, forceRefresh = true)
        }
        val refreshedUrl = refreshed.imageUrl.toHttpUrl()
        if (refreshedUrl.isLocalCachedPage(baseUrl.toHttpUrl().host)) {
            val retryFile = File(pageCacheDir, refreshedUrl.pathSegments.last())
            if (retryFile.exists() && retryFile.length() > 0L) {
                val mime = refreshedUrl.queryParameter("mime") ?: "image/webp"
                val body = retryFile.source().buffer().asResponseBody(mime.toMediaTypeOrNull(), retryFile.length())
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
        }
        return chain.proceed(buildImageRequest(refreshed.imageUrl, headers, baseUrl))
    }

    private suspend fun resolvePageImage(
        token: PageTokenDto,
        readerUrl: String,
        forceRefresh: Boolean,
    ): ResolvedPage {
        val sessionKey = "${token.m}/${token.c}"
        val session = chapterSessions.getOrPut(sessionKey) {
            ChapterSession(token.m, token.c, readerUrl, token.t)
        }

        if (!forceRefresh) {
            session.resolvedPages[token.p]?.let { return it }
        } else {
            session.resolvedPages.remove(token.p)
        }

        if (!session.initialized) {
            session.mutex.withLock {
                if (!session.initialized) {
                    val access = requestChapterAccess(token.m, token.c, readerUrl, forceVerify = false)
                    session.applyAccess(access, token.t)
                }
            }
            if (!forceRefresh) {
                session.resolvedPages[token.p]?.let { return it }
            }
        }

        return if (session.protectedPages) {
            resolveProtectedSequencePage(session, token.p, readerUrl, forceRefresh)
        } else {
            resolveGrantPage(session, token.p, readerUrl, forceRefresh)
        }
    }

    private suspend fun resolveProtectedSequencePage(
        session: ChapterSession,
        targetPage: Int,
        readerUrl: String,
        forceRefresh: Boolean,
    ): ResolvedPage = session.mutex.withLock {
        if (!forceRefresh) {
            session.resolvedPages[targetPage]?.let { return@withLock it }
        } else {
            session.resolvedPages.remove(targetPage)
        }

        for (attempt in 0..1) {
            var current = session.nextHandle
            if (current == null || (current.pageNumber ?: 1) > targetPage) {
                val freshAccess = requestChapterAccess(session.mangaId, session.chapterId, readerUrl, forceVerify = false)
                session.applyAccess(freshAccess, session.totalPages)
                if (!session.protectedPages) {
                    session.resolvedPages[targetPage]?.let { return@withLock it }
                    return@withLock resolveGrantPageUnlocked(session, targetPage, readerUrl)
                }
                current = session.nextHandle ?: throw IOException("Handle de página protegida ausente.")
            }

            var expiredDuringLoop = false
            while (current != null && (current.pageNumber ?: 1) <= targetPage) {
                when (val step = fetchProtectedStep(session, current, readerUrl)) {
                    is StepOutcome.Success -> {
                        session.resolvedPages[step.pageNumber] = step.page
                        session.nextHandle = step.nextHandle
                        if (step.pageNumber == targetPage) {
                            return@withLock step.page
                        }
                        current = step.nextHandle
                    }
                    StepOutcome.ExpiredHandle -> {
                        session.nextHandle = null
                        expiredDuringLoop = true
                        break
                    }
                    StepOutcome.VerificationRequired -> {
                        val verifiedAccess = requestChapterAccess(
                            session.mangaId,
                            session.chapterId,
                            readerUrl,
                            forceVerify = true,
                        )
                        session.applyAccess(verifiedAccess, session.totalPages)
                        if (!session.protectedPages) {
                            return@withLock resolveGrantPageUnlocked(session, targetPage, readerUrl)
                        }
                        current = session.nextHandle
                    }
                }
            }

            session.resolvedPages[targetPage]?.let { return@withLock it }
            if (!expiredDuringLoop && attempt == 0) {
                session.nextHandle = null
            }
        }

        throw IOException("Não foi possível carregar a página $targetPage do capítulo.")
    }

    private suspend fun fetchProtectedStep(
        session: ChapterSession,
        handle: HandleDto,
        readerUrl: String,
    ): StepOutcome {
        val encodedToken = URLEncoder.encode(handle.token, "UTF-8").replace("+", "%20")
        val url = "$apiUrl/reader/p".toHttpUrl().newBuilder()
            .addEncodedPathSegment(encodedToken)
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .fetch(Fetch.XHR.from(readerUrl))
            .build()

        return executeRequest(request, ensureSuccess = false).use { response ->
            if (response.code == 404) {
                return@use StepOutcome.ExpiredHandle
            }
            if (response.code == 403) {
                val error = runCatching { response.parseAs<ReaderAccessErrorDto>() }.getOrNull()
                if (error?.requiresTurnstile == true || error?.error == MangaLivre.READER_VERIFICATION_REQUIRED) {
                    return@use StepOutcome.VerificationRequired
                }
                throw IOException(error?.error ?: "Acesso negado à página (HTTP 403).")
            }
            if (!response.isSuccessful) {
                throw HttpException(response.code)
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if ("application/json" in contentType) {
                val dto = response.parseAs<PageResponseDto>()
                val rawUrl = dto.imageUrl?.toValidImageUrl()
                    ?: throw IOException("URL da imagem protegida inválida.")
                val stepPage = dto.pageNumber ?: handle.pageNumber ?: 1
                val stepCount = dto.pageCount ?: handle.pageCount ?: session.totalPages
                val nextToken = dto.nextHandle?.token?.trim().orEmpty()
                val nextPage = dto.nextHandle?.pageNumber ?: 0
                val nextHandle = if (nextToken.isNotEmpty() && nextPage > stepPage) {
                    HandleDto(
                        token = nextToken,
                        pageNumber = nextPage,
                        pageCount = dto.nextHandle?.pageCount ?: stepCount,
                    )
                } else {
                    null
                }
                val isProtectedEdge = dto.protectedEdge || rawUrl.toHttpUrl().isProtectedEdgeImage()
                return@use StepOutcome.Success(
                    pageNumber = stepPage,
                    page = ResolvedPage(
                        imageUrl = rawUrl,
                        protectedEdge = isProtectedEdge,
                    ),
                    nextHandle = nextHandle,
                )
            }

            if (contentType.startsWith("image/")) {
                val stepPage = response.header("x-tl-reader-page")?.toIntOrNull()
                    ?: handle.pageNumber
                    ?: 1
                val stepCount = response.header("x-tl-reader-page-count")?.toIntOrNull()
                    ?: handle.pageCount
                    ?: session.totalPages
                val nextToken = response.header("x-tl-reader-next-handle")?.trim().orEmpty()
                val nextPage = response.header("x-tl-reader-next-page")?.toIntOrNull() ?: 0
                val nextHandle = if (nextToken.isNotEmpty() && nextPage > stepPage) {
                    HandleDto(token = nextToken, pageNumber = nextPage, pageCount = stepCount)
                } else {
                    null
                }

                val mime = contentType.substringBefore(';').trim().ifEmpty { "image/webp" }
                val cachedUrl = storeBinaryPage(
                    session = session,
                    pageNumber = stepPage,
                    mime = mime,
                    readerUrl = readerUrl,
                    response = response,
                )
                return@use StepOutcome.Success(
                    pageNumber = stepPage,
                    page = ResolvedPage(
                        imageUrl = cachedUrl,
                        protectedEdge = false,
                    ),
                    nextHandle = nextHandle,
                )
            }

            throw IOException(MangaLivre.NON_JSON_MESSAGE)
        }
    }

    private fun storeBinaryPage(
        session: ChapterSession,
        pageNumber: Int,
        mime: String,
        readerUrl: String,
        response: Response,
    ): String {
        pruneOldBinaryFiles()
        val safeName = "${session.mangaId}_${session.chapterId}_$pageNumber.img"
            .replace(UNSAFE_FILENAME_REGEX, "_")
        val targetFile = File(pageCacheDir, safeName)
        targetFile.sink().buffer().use { sink ->
            sink.writeAll(response.body.source())
        }
        val token = PageTokenDto(
            m = session.mangaId,
            c = session.chapterId,
            p = pageNumber,
            t = session.totalPages,
            r = readerUrl,
        )
        return "$baseUrl/$LOCAL_PAGE_SEGMENT/$safeName".toHttpUrl().newBuilder()
            .addQueryParameter("mime", mime)
            .fragment("$PAGE_FRAGMENT_PREFIX${token.toJsonString()}")
            .build()
            .toString()
    }

    private fun pruneOldBinaryFiles() {
        val cutoff = System.currentTimeMillis() - BINARY_CACHE_TTL_MS
        pageCacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private suspend fun resolveGrantPage(
        session: ChapterSession,
        targetPage: Int,
        readerUrl: String,
        forceRefresh: Boolean,
    ): ResolvedPage {
        for (attempt in 0..1) {
            val now = System.currentTimeMillis()
            var grant = session.grant?.takeIf {
                (!forceRefresh || attempt > 0) && (it.expiresAt == null || it.expiresAt > now + 1_000L)
            }
            if (grant == null) {
                session.mutex.withLock {
                    val refreshedNow = System.currentTimeMillis()
                    val currentGrant = session.grant?.takeIf {
                        !forceRefresh && (it.expiresAt == null || it.expiresAt > refreshedNow + 1_000L)
                    }
                    if (currentGrant != null) {
                        grant = currentGrant
                    } else {
                        val freshAccess = requestChapterAccess(
                            session.mangaId,
                            session.chapterId,
                            readerUrl,
                            forceVerify = false,
                        )
                        session.applyAccess(freshAccess, session.totalPages)
                        if (session.protectedPages) {
                            return resolveProtectedSequencePage(session, targetPage, readerUrl, forceRefresh = false)
                        }
                        session.resolvedPages[targetPage]?.let { return it }
                        grant = session.grant ?: throw IOException("Token de acesso ao capítulo ausente.")
                    }
                }
            }

            val grantHeaders = headers.newBuilder()
                .set("x-tl-reader-grant", grant!!.token)
                .build()
            val pageEndpoint = "$apiUrl/reader/chapter".toHttpUrl().newBuilder()
                .addPathSegment(session.mangaId)
                .addPathSegment(session.chapterId)
                .addPathSegment("page")
                .addPathSegment(targetPage.toString())
                .build()

            val request = Request.Builder()
                .url(pageEndpoint)
                .headers(grantHeaders)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .fetch(Fetch.XHR.from(readerUrl))
                .build()

            val outcome = executeRequest(request, ensureSuccess = false).use { response ->
                when {
                    response.isSuccessful -> {
                        val dto = response.parseJson<PageResponseDto>()
                        val imageUrl = dto.imageUrl?.toValidImageUrl()
                            ?: throw IOException("URL da imagem inválida.")
                        val isProtectedEdge = dto.protectedEdge || imageUrl.toHttpUrl().isProtectedEdgeImage()
                        ResolvedPage(
                            imageUrl = imageUrl,
                            protectedEdge = isProtectedEdge,
                        ).also { session.resolvedPages[targetPage] = it }
                    }
                    response.code == 404 && attempt == 0 -> {
                        session.grant = null
                        null
                    }
                    response.code == 403 && attempt == 0 -> {
                        val error = runCatching { response.parseAs<ReaderAccessErrorDto>() }.getOrNull()
                        if (error?.requiresTurnstile == true || error?.error == MangaLivre.READER_VERIFICATION_REQUIRED) {
                            session.mutex.withLock {
                                val verified = requestChapterAccess(
                                    session.mangaId,
                                    session.chapterId,
                                    readerUrl,
                                    forceVerify = true,
                                )
                                session.applyAccess(verified, session.totalPages)
                            }
                            null
                        } else {
                            throw IOException(error?.error ?: "Acesso negado à página (HTTP 403).")
                        }
                    }
                    else -> throw HttpException(response.code)
                }
            }

            if (outcome != null) return outcome
        }

        throw IOException("Não foi possível carregar a página $targetPage.")
    }

    private suspend fun resolveGrantPageUnlocked(
        session: ChapterSession,
        targetPage: Int,
        readerUrl: String,
    ): ResolvedPage {
        val grant = session.grant ?: throw IOException("Token de acesso ao capítulo ausente.")
        val grantHeaders = headers.newBuilder()
            .set("x-tl-reader-grant", grant.token)
            .build()
        val pageEndpoint = "$apiUrl/reader/chapter".toHttpUrl().newBuilder()
            .addPathSegment(session.mangaId)
            .addPathSegment(session.chapterId)
            .addPathSegment("page")
            .addPathSegment(targetPage.toString())
            .build()

        val request = Request.Builder()
            .url(pageEndpoint)
            .headers(grantHeaders)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .fetch(Fetch.XHR.from(readerUrl))
            .build()

        return executeRequest(request, ensureSuccess = true).parseJson<PageResponseDto>().let { dto ->
            val imageUrl = dto.imageUrl?.toValidImageUrl()
                ?: throw IOException("URL da imagem inválida.")
            val isProtectedEdge = dto.protectedEdge || imageUrl.toHttpUrl().isProtectedEdgeImage()
            ResolvedPage(
                imageUrl = imageUrl,
                protectedEdge = isProtectedEdge,
            ).also { session.resolvedPages[targetPage] = it }
        }
    }

    private suspend fun requestChapterAccess(
        mangaId: String,
        chapterId: String,
        readerUrl: String,
        forceVerify: Boolean,
    ): ReaderAccessResponseDto {
        val generationBefore = verificationGeneration.get()
        if (!forceVerify) {
            postAccessRequest(mangaId, chapterId, readerUrl, turnstileToken = null)?.let { return it }
        }

        return verificationMutex.withLock {
            if (!forceVerify && verificationGeneration.get() != generationBefore) {
                postAccessRequest(mangaId, chapterId, readerUrl, turnstileToken = null)?.let { return@withLock it }
            }

            val token = turnstileSolver(readerUrl)
            val verified = postAccessRequest(mangaId, chapterId, readerUrl, turnstileToken = token)
                ?: throw IOException("Não foi possível validar o acesso ao leitor. Tente novamente.")
            verificationGeneration.incrementAndGet()
            runCatching { CookieManager.getInstance().flush() }
            verified
        }
    }

    private suspend fun postAccessRequest(
        mangaId: String,
        chapterId: String,
        readerUrl: String,
        turnstileToken: String?,
    ): ReaderAccessResponseDto? {
        val csrf = csrfToken()
        val reqHeaders = headers.newBuilder().apply {
            if (csrf != null) {
                set("x-csrf-token", csrf)
            }
        }.build()

        val body = if (turnstileToken.isNullOrEmpty()) {
            ReaderAccessRequestDto(mangaId, chapterId).toJsonRequestBody()
        } else {
            ReaderVerifiedAccessRequestDto(mangaId, chapterId, turnstileToken).toJsonRequestBody()
        }

        val request = Request.Builder()
            .url("$apiUrl/reader/chapter/access")
            .headers(reqHeaders)
            .post(body)
            .fetch(Fetch.XHR.from(readerUrl))
            .build()

        return executeRequest(request, ensureSuccess = false).use { response ->
            if (response.isSuccessful) {
                return@use response.parseJson<ReaderAccessResponseDto>()
            }
            if (response.code == 403) {
                val error = runCatching { response.parseAs<ReaderAccessErrorDto>() }.getOrNull()
                if (turnstileToken == null &&
                    (error?.requiresTurnstile == true || error?.error == MangaLivre.READER_VERIFICATION_REQUIRED)
                ) {
                    return@use null
                }
                if (error?.interactivePending == true) {
                    throw IOException("A verificação anti-bot está pendente. Aguarde alguns instantes e tente novamente.")
                }
                throw IOException(error?.error ?: "Acesso recusado ao capítulo (HTTP 403).")
            }
            throw HttpException(response.code)
        }
    }

    private suspend fun executeRequest(request: Request, ensureSuccess: Boolean): Response {
        var attempt = 0
        while (true) {
            val executor = callExecutor
            val response = if (executor != null) {
                executor(client, request, ensureSuccess)
            } else {
                client.call(request, ensureSuccess)
            }
            if ((response.code != 503 && response.code != 429) || attempt >= MAX_TRANSIENT_RETRIES) {
                return response
            }
            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
            response.close()
            val waitMs = if (retryAfterSec != null && retryAfterSec > 0L) {
                (retryAfterSec * 1_000L).coerceAtMost(MAX_RETRY_DELAY_MS)
            } else {
                (transientRetryBaseDelayMs * (attempt + 1)).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
            attempt++
            delay(waitMs.milliseconds)
        }
    }

    private class ChapterSession(
        val mangaId: String,
        val chapterId: String,
        val readerUrl: String,
        @Volatile var totalPages: Int,
    ) {
        val mutex = Mutex()
        val resolvedPages = ConcurrentHashMap<Int, ResolvedPage>()
        val prefetchInFlight = AtomicBoolean(false)
        val prefetchTargetPage = AtomicInteger(0)

        @Volatile
        var initialized: Boolean = false

        @Volatile
        var protectedPages: Boolean = false

        @Volatile
        var nextHandle: HandleDto? = null

        @Volatile
        var grant: GrantDto? = null

        @Volatile
        var updatedAt: Long = 0L

        fun applyAccess(access: ReaderAccessResponseDto, fallbackTotal: Int) {
            val resolvedCount = access.chapter?.pageCount
                ?: access.pageCount
                ?: access.firstHandle?.pageCount
                ?: fallbackTotal
            if (resolvedCount > 0) {
                totalPages = resolvedCount
            }
            access.chapter?.pages?.forEachIndexed { index, raw ->
                raw.toValidImageUrl()?.let { validUrl ->
                    resolvedPages[index + 1] = ResolvedPage(
                        imageUrl = validUrl,
                        protectedEdge = validUrl.toHttpUrl().isProtectedEdgeImage(),
                    )
                }
            }
            protectedPages = access.protectedPages
            nextHandle = access.firstHandle
            grant = access.grant
            initialized = true
            updatedAt = System.currentTimeMillis()
        }

        fun isReusableForPageList(now: Long): Boolean {
            if (!initialized || totalPages <= 0) return false
            if (protectedPages) {
                return now - updatedAt < SESSION_REUSE_TTL_MS &&
                    (nextHandle != null || resolvedPages.isNotEmpty())
            }
            val activeGrant = grant ?: return false
            return activeGrant.expiresAt?.let { it > now + 5_000L } ?: (now - updatedAt < SESSION_REUSE_TTL_MS)
        }
    }

    internal class ResolvedPage(
        val imageUrl: String,
        val protectedEdge: Boolean,
    )

    private sealed interface StepOutcome {
        class Success(
            val pageNumber: Int,
            val page: ResolvedPage,
            val nextHandle: HandleDto?,
        ) : StepOutcome

        data object ExpiredHandle : StepOutcome
        data object VerificationRequired : StepOutcome
    }

    companion object {
        internal const val PAGE_FRAGMENT_PREFIX = "tl:"
        internal const val LOCAL_PAGE_SEGMENT = "_tl_local_page"

        private const val MAX_CHAPTER_SESSIONS = 6
        private const val MAX_TRANSIENT_RETRIES = 3
        private const val MAX_RETRY_DELAY_MS = 3_000L
        private const val SESSION_REUSE_TTL_MS = 600_000L
        private const val INITIAL_PREFETCH_PAGES = 5
        private const val SLIDING_PREFETCH_WINDOW = 3
        private const val BINARY_CACHE_TTL_MS = 300_000L
        private val UNSAFE_FILENAME_REGEX = Regex("[^A-Za-z0-9._-]")
    }
}

internal inline fun <reified T> Response.parseJson(): T {
    val peek = peekBody(MAX_PEEK_BYTES).string().trimStart()
    if (peek.isEmpty() || peek.startsWith("<")) {
        close()
        throw IOException(MangaLivre.NON_JSON_MESSAGE)
    }
    return parseAs()
}

internal fun String.toValidImageUrl(): String? {
    val url = trim().toHttpUrlOrNull() ?: return null
    if (!url.isHttps) return null
    val unwrapped = url.queryParameter("url")?.toHttpUrlOrNull()
    if (unwrapped != null && unwrapped.isHttps) {
        return unwrapped.toString()
    }
    return url.toString()
}

internal fun HttpUrl.isProtectedEdgeImage(): Boolean = pathSegments.size >= 3 && pathSegments[0] == "reader" && pathSegments[1] == "image" && pathSegments[2].isNotEmpty()

private fun HttpUrl.isLocalCachedPage(baseHost: String): Boolean = host == baseHost && pathSegments.size >= 2 && pathSegments[0] == ReaderCoordinator.LOCAL_PAGE_SEGMENT

private const val MAX_PEEK_BYTES = 1024L
