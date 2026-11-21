package eu.kanade.tachiyomi.extension.pt.fliptru

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

/** Kept apart from `SChapter` because `SChapter.create()` is a stub on the JVM and cannot run in unit tests. */
internal data class ChapterEntry(val path: String, val name: String, val number: Float)

private val chapterSlugRegex = Regex("/comic/[^/]+/([^/?#]+)")

internal fun parseChapterList(document: Document): List<ChapterEntry> {
    return document.select(".chapter-list-item a[href]")
        .mapNotNull { link ->
            val path = link.attr("abs:href").toHttpUrlOrNull()?.encodedPath
                ?: return@mapNotNull null
            val slug = chapterSlugRegex.find(path)?.groupValues?.get(1) ?: return@mapNotNull null
            ChapterEntry(
                path = path,
                name = link.text().ifBlank { "Capítulo $slug" },
                number = slug.toFloatOrNull() ?: 0f,
            )
        }
        .distinctBy { it.path }
        .sortedByDescending { it.number }
}

/** The site shows 10 chapters per page; the "load more" button carries the URL of the next HTML fragment. */
internal fun nextChaptersPageUrl(document: Document): String? = document.selectFirst("#chapters-load-more a[data-url]")?.attr("data-url")?.takeIf { it.isNotBlank() }

internal suspend fun fetchAllChapters(
    first: Document,
    fetchPage: suspend (String) -> Document,
): List<ChapterEntry> {
    val entries = parseChapterList(first).toMutableList()
    val fetched = mutableSetOf<String>()
    var next = nextChaptersPageUrl(first)
    // `fetched.add` is false for a URL already requested, which stops a button that points back at itself.
    while (next != null && fetched.add(next)) {
        val page = fetchPage(next)
        entries += parseChapterList(page)
        next = nextChaptersPageUrl(page)
    }
    return entries.distinctBy { it.path }.sortedByDescending { it.number }
}

// Two reader layouts coexist: older series use div.comic_page_image, newer ones a carousel of div.comic-page-image
// (the carousel also keeps an empty comic_page_image placeholder, which has no <img>).
internal fun parsePageUrls(document: Document): List<String> = document.select("div.comic_page_image img, div.comic-page-image img")
    .mapNotNull { img ->
        img.attr("abs:data-src")
            .ifBlank { img.attr("abs:src") }
            .takeIf { it.startsWith("http") }
    }
    .distinct()
