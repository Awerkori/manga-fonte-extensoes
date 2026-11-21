package eu.kanade.tachiyomi.extension.pt.argoscomics

// aniargos.com is a Next.js app: search, details, chapters, pages and login are Server Actions called by id.
// The ids change with every deploy of the site, so these are only the starting point: when the site answers
// "Server action not found" the id is read again from the site's client bundles, where each one is printed
// next to its function name, as createServerReference("<id>", ..., "<name>").
internal val DEFAULT_ACTION_IDS = mapOf(
    "search" to "40f7fc790f56cdff7097f3d172bf666a20e2d2d4a5",
    "getOne" to "605b188667d9bcd4a1fe806764ae5e8861eff89915",
    "getAllChapters" to "60a7496d813253cd0acc35e49c36ec1437f4f9f1eb",
    "getPages" to "701b928d969fea96b2124f4f8453277895cb6fae3b",
    "login" to "609bd1e998680b44c6b27717e3622b0d59f27ad3f9",
)

private val CHUNK_REGEX = Regex("""/_next/static/chunks/([\w.~-]+\.js)""")
private val ACTION_REGEX = Regex("""createServerReference\)?\(\s*"([0-9a-f]{40,})"\s*,[^)]*?,\s*"(\w+)"\s*\)""")

internal fun chunkNames(html: String): List<String> = CHUNK_REGEX.findAll(html).map { it.groupValues[1] }.distinct().toList()

internal fun actionIdIn(js: String, name: String): String? = ACTION_REGEX.findAll(js).firstOrNull { it.groupValues[2] == name }?.groupValues?.get(1)

internal fun isStaleAction(code: Int, body: String) = code == 404 && body.contains("Server action not found")

// the login answers 200 either way; a user object means it worked
internal fun loggedIn(body: String): Boolean = body.lineSequence().any { it.startsWith("1:") && it.contains(""""user":{""") }
