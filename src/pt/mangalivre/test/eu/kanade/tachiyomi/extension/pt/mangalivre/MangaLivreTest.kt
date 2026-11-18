package eu.kanade.tachiyomi.extension.pt.mangalivre

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MangaLivreTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupInjekt() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }

    private val syncExecutor: suspend (OkHttpClient, Request, Boolean) -> Response = { c, req, ensureSuccess ->
        withContext(Dispatchers.IO) {
            val resp = c.newCall(req).execute()
            if (ensureSuccess && !resp.isSuccessful) {
                resp.close()
                throw HttpException(resp.code)
            }
            resp
        }
    }

    @Test
    fun `parses search wrapper and manga metadata accurately`() {
        val searchJson = """
            {
              "mangas": [
                {
                  "id": "obra-5da85fb8",
                  "title": "O Psicopata Recupera Sua Sanidade",
                  "coverUrl": "https://cdn.toonlivre.net/covers/obra-5da85fb8/cover.webp",
                  "type": "Manhwa",
                  "authors": ["김현우"],
                  "artists": ["N/A"],
                  "status": "On Hold",
                  "genres": ["Ação", "Fantasia"],
                  "description": "Sinopse principal.",
                  "alternativeTitle": "The Crazy Villain Regains His Sanity",
                  "chapters": [
                    {
                      "id": "cap-7b9de29d-26",
                      "number": "26",
                      "title": "",
                      "timestamp": 1794951697706,
                      "pageCount": 14
                    }
                  ]
                }
              ],
              "pagination": {
                "hasNextPage": true
              }
            }
        """.trimIndent()

        val wrapper = searchJson.parseAs<WrapperDto>()
        assertTrue(wrapper.hasNextPage)
        assertEquals(1, wrapper.mangas.size)

        val dto = wrapper.mangas.first()
        val mangaPt = dto.toSManga(useAlternativeTitle = false, createManga = ::fakeManga)
        assertEquals("obra-5da85fb8", mangaPt.url)
        assertEquals("O Psicopata Recupera Sua Sanidade", mangaPt.title)
        assertEquals("김현우", mangaPt.author)
        assertNull(mangaPt.artist)
        assertEquals("Manhwa, Ação, Fantasia", mangaPt.genre)
        assertEquals(SManga.ON_HIATUS, mangaPt.status)
        assertTrue(mangaPt.description!!.contains("Nome alternativo: The Crazy Villain Regains His Sanity"))

        val mangaAlt = dto.toSManga(useAlternativeTitle = true, createManga = ::fakeManga)
        assertEquals("The Crazy Villain Regains His Sanity", mangaAlt.title)

        val chapters = dto.toSChapterList(createChapter = ::fakeChapter)
        assertEquals(1, chapters.size)
        val ch = chapters.first()
        assertEquals("/o-psicopata-recupera-sua-sanidade/26", ch.url)
        assertEquals("Capítulo 26", ch.name)
        assertEquals(26f, ch.chapter_number, 0.001f)
        assertEquals("obra-5da85fb8", ch.memo["mangaId"]?.stringOrNull)
        assertEquals("cap-7b9de29d-26", ch.memo["chapterId"]?.stringOrNull)
    }

    @Test
    fun `unwraps edgecompute proxy urls and identifies protected edge image urls`() {
        val proxied = "https://slightly-free-mayfly.edgecompute.app/?url=https%3A%2F%2Fcdn.toonlivre.net%2Fobras%2Fobra-1%2F01%2F001.webp"
        assertEquals("https://cdn.toonlivre.net/obras/obra-1/01/001.webp", proxied.toValidImageUrl())
        assertFalse("https://cdn.toonlivre.net/obras/obra-1/01/001.webp".toHttpUrl().isProtectedEdgeImage())

        val protectedEdge = "https://slightly-free-mayfly.edgecompute.app/reader/image/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCdE"
        assertEquals(protectedEdge, protectedEdge.toValidImageUrl())
        assertTrue(protectedEdge.toHttpUrl().isProtectedEdgeImage())
    }

    @Test
    fun `loads all 60 protected pages lazily and survives transient 503 and expired 404 handles without truncating`() = runBlocking {
        val totalPages = 60
        val turnstileCalls = AtomicInteger(0)
        val verifiedSession = AtomicBoolean(false)
        val injected503OnPage25 = AtomicBoolean(false)
        val injected404OnPage42 = AtomicBoolean(false)

        val client = createMockClient { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/reader/chapter/access" -> {
                    val bodyStr = request.bodyString()
                    if (!verifiedSession.get() && !bodyStr.contains("turnstileToken")) {
                        jsonResponse(
                            request,
                            403,
                            """{"error":"Reader verification required","requiresTurnstile":true}""",
                        )
                    } else {
                        if (bodyStr.contains("turnstile-ok")) {
                            verifiedSession.set(true)
                        }
                        jsonResponse(
                            request,
                            200,
                            """
                            {
                              "chapter": {"pageCount": $totalPages, "pages": []},
                              "protectedPages": true,
                              "firstHandle": {"token": "tok-1", "pageNumber": 1, "pageCount": $totalPages}
                            }
                            """.trimIndent(),
                        )
                    }
                }
                path.startsWith("/api/reader/p/") -> {
                    val token = path.removePrefix("/api/reader/p/")
                    val pageNum = token.removePrefix("tok-").toInt()
                    if (pageNum == 25 && injected503OnPage25.compareAndSet(false, true)) {
                        jsonResponse(request, 503, """{"error":"Service temporarily unavailable"}""")
                    } else if (pageNum == 42 && injected404OnPage42.compareAndSet(false, true)) {
                        jsonResponse(request, 404, """{"error":"Handle expired"}""")
                    } else {
                        val nextJson = if (pageNum < totalPages) {
                            """{"token":"tok-${pageNum + 1}","pageNumber":${pageNum + 1},"pageCount":$totalPages}"""
                        } else {
                            "null"
                        }
                        jsonResponse(
                            request,
                            200,
                            """
                            {
                              "imageUrl": "https://cdn.toonlivre.net/obras/obra-1/10/${pageNum.toString().padStart(3, '0')}.webp",
                              "directImage": true,
                              "protectedEdge": false,
                              "pageNumber": $pageNum,
                              "pageCount": $totalPages,
                              "nextHandle": $nextJson
                            }
                            """.trimIndent(),
                        )
                    }
                }
                else -> jsonResponse(request, 404, """{"error":"Not found"}""")
            }
        }

        val coordinator = ReaderCoordinator(
            clientProvider = { client },
            headersProvider = { Headers.headersOf("User-Agent", "TestBrowser/1.0") },
            baseUrlProvider = { "https://toonlivre.net" },
            turnstileSolver = {
                turnstileCalls.incrementAndGet()
                "turnstile-ok"
            },
            transientRetryBaseDelayMs = 1L,
            enableBackgroundTasks = false,
            callExecutor = syncExecutor,
        )

        val pages = coordinator.getPageList(
            mangaId = "obra-1",
            chapterId = "cap-10",
            readerUrl = "https://toonlivre.net/manga-teste/10",
            fallbackPageCount = null,
        )

        assertEquals(totalPages, pages.size)
        assertEquals("https://cdn.toonlivre.net/obras/obra-1/10/001.webp", pages[0].imageUrl)
        assertNull(pages[1].imageUrl)
        assertEquals(1, turnstileCalls.get())

        val resolvedUrls = pages.map { page -> page.imageUrl ?: coordinator.getImageUrl(page.url) }
        assertEquals(totalPages, resolvedUrls.size)
        assertEquals("https://cdn.toonlivre.net/obras/obra-1/10/001.webp", resolvedUrls.first())
        assertEquals("https://cdn.toonlivre.net/obras/obra-1/10/060.webp", resolvedUrls.last())
        assertTrue(injected503OnPage25.get())
        assertTrue(injected404OnPage42.get())
        assertEquals(1, turnstileCalls.get())
    }

    @Test
    fun `supports grant mode and refreshes expired protected edge image tokens automatically`() = runBlocking {
        val page1Calls = AtomicInteger(0)
        val grantRefreshes = AtomicInteger(0)

        lateinit var coordinator: ReaderCoordinator
        val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
        val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host].orEmpty()
        }
        cookieStore["toonlivre.net"] = listOf(
            Cookie.Builder().name("csrf_token").value("csrf-secret").domain("toonlivre.net").build(),
        )
        cookieStore["slightly-free-mayfly.edgecompute.app"] = listOf(
            Cookie.Builder().name("should_be_omitted").value("1").domain("slightly-free-mayfly.edgecompute.app").build(),
        )

        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor { chain -> coordinator.interceptImage(chain) }
            .addInterceptor { chain ->
                // Simulate BridgeInterceptor attaching cookies from CookieJar before network interceptors run
                val req = chain.request()
                val cookies = cookieJar.loadForRequest(req.url)
                val withCookies = if (cookies.isNotEmpty() && req.header("Cookie") == null) {
                    req.newBuilder()
                        .header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
                        .build()
                } else {
                    req
                }
                val wireReq = if (withCookies.url.isProtectedEdgeImage()) {
                    withCookies.newBuilder()
                        .removeHeader("Cookie")
                        .removeHeader("Referer")
                        .build()
                } else {
                    withCookies
                }
                chain.proceed(wireReq)
            }
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                when {
                    path == "/api/reader/chapter/access" -> {
                        assertEquals("csrf-secret", req.header("x-csrf-token"))
                        val n = grantRefreshes.incrementAndGet()
                        jsonResponse(
                            req,
                            200,
                            """
                            {
                              "chapter": {"pageCount": 2, "pages": []},
                              "protectedPages": false,
                              "grant": {"token": "grant-$n", "expiresAt": ${System.currentTimeMillis() + 60_000L}}
                            }
                            """.trimIndent(),
                        )
                    }
                    path == "/api/reader/chapter/obra-2/cap-2/page/1" -> {
                        val callNum = page1Calls.incrementAndGet()
                        val token = if (callNum == 1) {
                            "expiredEdgeToken_012345678901234567890123456"
                        } else {
                            "freshEdgeToken___012345678901234567890123456"
                        }
                        jsonResponse(
                            req,
                            200,
                            """
                            {
                              "imageUrl": "https://slightly-free-mayfly.edgecompute.app/reader/image/$token",
                              "directImage": true,
                              "protectedEdge": true,
                              "pageNumber": 1,
                              "pageCount": 2
                            }
                            """.trimIndent(),
                        )
                    }
                    path.startsWith("/reader/image/") -> {
                        assertNull(req.header("Cookie"))
                        assertNull(req.header("Referer"))
                        assertEquals("https://toonlivre.net", req.header("Origin"))

                        val tok = req.url.pathSegments.last()
                        if (tok.startsWith("expiredEdgeToken")) {
                            jsonResponse(req, 404, """{"error":"Token expired"}""")
                        } else {
                            Response.Builder()
                                .request(req)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(byteArrayOf(1, 2, 3, 4).toResponseBody("image/webp".toMediaType()))
                                .build()
                        }
                    }
                    else -> jsonResponse(req, 404, """{}""")
                }
            }
            .build()

        coordinator = ReaderCoordinator(
            clientProvider = { client },
            headersProvider = {
                Headers.headersOf(
                    "User-Agent",
                    "Mozilla/5.0 Chrome/134.0.0.0",
                    "Referer",
                    "https://toonlivre.net/",
                )
            },
            baseUrlProvider = { "https://toonlivre.net" },
            turnstileSolver = { "unused" },
            transientRetryBaseDelayMs = 1L,
            enableBackgroundTasks = false,
            callExecutor = syncExecutor,
        )

        val pages = coordinator.getPageList("obra-2", "cap-2", "https://toonlivre.net/obra-2/2", null)
        assertEquals(2, pages.size)
        val imageUrlWithFragment = pages[0].imageUrl
        assertNotNull(imageUrlWithFragment)
        assertTrue(imageUrlWithFragment!!.contains("expiredEdgeToken_"))
        assertEquals(1, page1Calls.get())

        val imageReq = coordinator.buildImageRequest(
            imageUrl = imageUrlWithFragment,
            baseHeaders = Headers.headersOf("User-Agent", "Mozilla/5.0 Chrome/134.0.0.0", "Referer", "https://toonlivre.net/"),
            baseUrl = "https://toonlivre.net",
        )

        client.newCall(imageReq).execute().use { resp ->
            assertEquals(200, resp.code)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), resp.body.bytes())
        }
        assertEquals(2, page1Calls.get())
    }

    @Test
    fun `supports direct binary image responses from protected handle endpoint`() = runBlocking {
        val sampleBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        lateinit var coordinator: ReaderCoordinator

        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> coordinator.interceptImage(chain) }
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                when {
                    path == "/api/reader/chapter/access" -> jsonResponse(
                        req,
                        200,
                        """
                        {
                          "chapter": {"pageCount": 2, "pages": []},
                          "protectedPages": true,
                          "firstHandle": {"token": "bin-1", "pageNumber": 1, "pageCount": 2}
                        }
                        """.trimIndent(),
                    )
                    path == "/api/reader/p/bin-1" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/webp")
                        .header("x-tl-reader-page", "1")
                        .header("x-tl-reader-page-count", "2")
                        .header("x-tl-reader-next-handle", "bin-2")
                        .header("x-tl-reader-next-page", "2")
                        .body(sampleBytes.toResponseBody("image/webp".toMediaType()))
                        .build()
                    path == "/api/reader/p/bin-2" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/webp")
                        .header("x-tl-reader-page", "2")
                        .header("x-tl-reader-page-count", "2")
                        .body(sampleBytes.toResponseBody("image/webp".toMediaType()))
                        .build()
                    else -> jsonResponse(req, 404, """{}""")
                }
            }
            .build()

        coordinator = ReaderCoordinator(
            clientProvider = { client },
            headersProvider = { Headers.headersOf("User-Agent", "Test/1.0") },
            baseUrlProvider = { "https://toonlivre.net" },
            turnstileSolver = { "unused" },
            transientRetryBaseDelayMs = 1L,
            enableBackgroundTasks = false,
            callExecutor = syncExecutor,
        )

        val pages = coordinator.getPageList("obra-bin", "cap-bin", "https://toonlivre.net/obra-bin/1", null)
        assertEquals(2, pages.size)
        assertNotNull(pages[0].imageUrl)

        for (page in pages) {
            val resolvedUrl = page.imageUrl ?: coordinator.getImageUrl(page.url)
            val req = coordinator.buildImageRequest(resolvedUrl, Headers.headersOf(), "https://toonlivre.net")
            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                assertArrayEquals(sampleBytes, resp.body.bytes())
            }
        }
    }

    @Test
    fun `pre-resolves first page in getPageList and prefetches ahead pages in background`() = runBlocking {
        val totalPages = 8
        val handleRequests = AtomicInteger(0)

        val client = createMockClient { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/reader/chapter/access" -> jsonResponse(
                    request,
                    200,
                    """
                    {
                      "chapter": {"pageCount": $totalPages, "pages": []},
                      "protectedPages": true,
                      "firstHandle": {"token": "tok-1", "pageNumber": 1, "pageCount": $totalPages}
                    }
                    """.trimIndent(),
                )
                path.startsWith("/api/reader/p/") -> {
                    handleRequests.incrementAndGet()
                    val pageNum = path.removePrefix("/api/reader/p/tok-").toInt()
                    val nextJson = if (pageNum < totalPages) {
                        """{"token":"tok-${pageNum + 1}","pageNumber":${pageNum + 1},"pageCount":$totalPages}"""
                    } else {
                        "null"
                    }
                    jsonResponse(
                        request,
                        200,
                        """
                        {
                          "imageUrl": "https://cdn.toonlivre.net/obras/obra-bg/1/${pageNum.toString().padStart(3, '0')}.webp",
                          "directImage": true,
                          "protectedEdge": false,
                          "pageNumber": $pageNum,
                          "pageCount": $totalPages,
                          "nextHandle": $nextJson
                        }
                        """.trimIndent(),
                    )
                }
                else -> jsonResponse(request, 404, """{}""")
            }
        }

        val coordinator = ReaderCoordinator(
            clientProvider = { client },
            headersProvider = { Headers.headersOf("User-Agent", "Test/1.0") },
            baseUrlProvider = { "https://toonlivre.net" },
            turnstileSolver = { "unused" },
            transientRetryBaseDelayMs = 1L,
            enableBackgroundTasks = true,
            callExecutor = syncExecutor,
        )

        val pages = coordinator.getPageList("obra-bg", "cap-1", "https://toonlivre.net/obra-bg/1", null)
        assertEquals(totalPages, pages.size)
        assertEquals("https://cdn.toonlivre.net/obras/obra-bg/1/001.webp", pages[0].imageUrl)
        assertNull(pages[1].imageUrl)

        withTimeout(2_000L) {
            while (!coordinator.isPageResolved("obra-bg", "cap-1", 5)) {
                delay(15L)
            }
        }
        val callsAfterInitialPrefetch = handleRequests.get()
        assertEquals(5, callsAfterInitialPrefetch)
        for (pageNumber in 2..5) {
            assertTrue(coordinator.isPageResolved("obra-bg", "cap-1", pageNumber))
        }

        assertEquals(
            "https://cdn.toonlivre.net/obras/obra-bg/1/003.webp",
            coordinator.getImageUrl(pages[2].url),
        )
        assertEquals(
            "https://cdn.toonlivre.net/obras/obra-bg/1/005.webp",
            coordinator.getImageUrl(pages[4].url),
        )
        // Prefetched pages must resolve immediately from cache without extra handle fetches (unless sliding ahead >= 6 starts).
        withTimeout(2_000L) {
            while (!coordinator.isPageResolved("obra-bg", "cap-1", 8)) {
                delay(15L)
            }
        }
        assertEquals(8, handleRequests.get())
    }

    @Test
    fun `keeps cached protected edge pages valid mid-chapter and refreshes only on 404 without extra Turnstile`() = runBlocking {
        val totalPages = 4
        val turnstileCalls = AtomicInteger(0)
        val accessCalls = AtomicInteger(0)
        val handleCalls = AtomicInteger(0)
        val failFirstEdgeImage = AtomicBoolean(true)
        var verified = false

        lateinit var coordinator: ReaderCoordinator
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> coordinator.interceptImage(chain) }
            .addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                when {
                    path == "/api/reader/chapter/access" -> {
                        accessCalls.incrementAndGet()
                        val bodyStr = request.bodyString()
                        if (!verified && !bodyStr.contains("turnstile-ok")) {
                            jsonResponse(
                                request,
                                403,
                                """{"error":"Reader verification required","requiresTurnstile":true}""",
                            )
                        } else {
                            verified = true
                            jsonResponse(
                                request,
                                200,
                                """
                                {
                                  "chapter": {"pageCount": $totalPages, "pages": []},
                                  "protectedPages": true,
                                  "firstHandle": {"token": "tok-1", "pageNumber": 1, "pageCount": $totalPages}
                                }
                                """.trimIndent(),
                            )
                        }
                    }
                    path.startsWith("/api/reader/p/") -> {
                        val callNum = handleCalls.incrementAndGet()
                        val pageNum = path.removePrefix("/api/reader/p/tok-").toInt()
                        val version = if (callNum > totalPages) "v2" else "v1"
                        val nextJson = if (pageNum < totalPages) {
                            """{"token":"tok-${pageNum + 1}","pageNumber":${pageNum + 1},"pageCount":$totalPages}"""
                        } else {
                            "null"
                        }
                        val edgeToken = "edge_${pageNum}_${version}_012345678901234567890123456789012"
                        jsonResponse(
                            request,
                            200,
                            """
                            {
                              "imageUrl": "https://slightly-free-mayfly.edgecompute.app/reader/image/$edgeToken",
                              "directImage": true,
                              "protectedEdge": true,
                              "pageNumber": $pageNum,
                              "pageCount": $totalPages,
                              "nextHandle": $nextJson
                            }
                            """.trimIndent(),
                        )
                    }
                    path.startsWith("/reader/image/") -> {
                        val edgeToken = request.url.pathSegments.last()
                        if (edgeToken.startsWith("edge_2_v1") && failFirstEdgeImage.compareAndSet(true, false)) {
                            jsonResponse(request, 404, """{"error":"Expired edge token"}""")
                        } else {
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(byteArrayOf(7, 8, 9).toResponseBody("image/webp".toMediaType()))
                                .build()
                        }
                    }
                    else -> jsonResponse(request, 404, """{}""")
                }
            }
            .build()

        coordinator = ReaderCoordinator(
            clientProvider = { client },
            headersProvider = { Headers.headersOf("User-Agent", "TestBrowser/1.0") },
            baseUrlProvider = { "https://toonlivre.net" },
            turnstileSolver = {
                turnstileCalls.incrementAndGet()
                "turnstile-ok"
            },
            transientRetryBaseDelayMs = 1L,
            enableBackgroundTasks = false,
            callExecutor = syncExecutor,
        )

        val pages = coordinator.getPageList("obra-edge", "cap-1", "https://toonlivre.net/obra-edge/1", null)
        assertEquals(totalPages, pages.size)
        assertEquals(1, turnstileCalls.get())
        assertEquals(2, accessCalls.get())

        val p2Url = coordinator.getImageUrl(pages[1].url)
        val p3Url = coordinator.getImageUrl(pages[2].url)
        val p4Url = coordinator.getImageUrl(pages[3].url)
        assertTrue(p2Url.contains("edge_2_v1"))
        assertTrue(p3Url.contains("edge_3_v1"))
        assertTrue(p4Url.contains("edge_4_v1"))

        // Revisiting an earlier page or re-opening the chapter must reuse cached session/URLs without extra access or Turnstile.
        assertEquals(p2Url, coordinator.getImageUrl(pages[1].url))
        val reusedPages = coordinator.getPageList("obra-edge", "cap-1", "https://toonlivre.net/obra-edge/1", null)
        assertEquals(p2Url, reusedPages[1].imageUrl)
        assertEquals(1, turnstileCalls.get())
        assertEquals(2, accessCalls.get())
        assertEquals(totalPages, handleCalls.get())

        // If the edge token actually returns 404, interceptImage refreshes it transparently using the active session.
        val imageReq = coordinator.buildImageRequest(p2Url, Headers.headersOf(), "https://toonlivre.net")
        client.newCall(imageReq).execute().use { resp ->
            assertEquals(200, resp.code)
            assertArrayEquals(byteArrayOf(7, 8, 9), resp.body.bytes())
        }
        assertEquals(1, turnstileCalls.get())
    }

    private fun fakeManga(): SManga = createPropertyBagProxy(SManga::class.java)

    private fun fakeChapter(): SChapter = createPropertyBagProxy(SChapter::class.java).apply {
        memo = buildJsonObject {}
    }

    private fun <T> createPropertyBagProxy(iface: Class<T>): T {
        val values = HashMap<String, Any?>()
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            val name = method.name
            when {
                name.startsWith("set") && args != null && args.size == 1 -> {
                    values[name.removePrefix("set").lowercase()] = args[0]
                    null
                }
                name.startsWith("get") && (args == null || args.isEmpty()) -> {
                    val key = name.removePrefix("get").lowercase()
                    values[key] ?: defaultPrimitive(method.returnType)
                }
                else -> defaultPrimitive(method.returnType)
            }
        } as T
    }

    private fun defaultPrimitive(type: Class<*>): Any? = when (type) {
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Boolean::class.javaPrimitiveType -> false
        else -> null
    }

    private fun createMockClient(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> handler(chain.request()) }
        .build()

    private fun jsonResponse(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .header("Content-Type", "application/json; charset=utf-8")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun Request.bodyString(): String {
        val buf = Buffer()
        body?.writeTo(buf)
        return buf.readUtf8()
    }
}
