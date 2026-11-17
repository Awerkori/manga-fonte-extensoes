package eu.kanade.tachiyomi.extension.pt.mangalivre

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer

@Serializable
class WrapperDto(
    val mangas: List<MangaDto>,
    private val pagination: PaginationDto,
) {
    val hasNextPage: Boolean get() = pagination.hasNextPage
}

@Serializable
class PaginationDto(
    val hasNextPage: Boolean,
)

@Serializable
class MangaDto(
    val id: String,
    private val title: String,
    private val coverUrl: String? = null,
    private val type: String? = null,
    private val authors: List<String>? = null,
    private val artists: List<String>? = null,
    private val genres: List<String>? = null,
    private val description: String? = null,
    private val alternativeTitle: String? = null,
    val chapters: List<ChapterDto>? = null,
    private val status: String? = null,
) {
    fun toSManga(
        useAlternativeTitle: Boolean,
        createManga: () -> SManga = SManga::create,
    ) = createManga().apply {
        url = id
        title = if (useAlternativeTitle && !alternativeTitle.isNullOrBlank()) {
            alternativeTitle
        } else {
            this@MangaDto.title
        }
        thumbnail_url = coverUrl
        author = authors.cleanNames()
        artist = artists.cleanNames()
        genre = buildList {
            type?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            genres?.forEach { item ->
                item.trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().takeIf(List<String>::isNotEmpty)?.joinToString()
        description = buildString {
            val cleanDesc = this@MangaDto.description?.trim()
            if (!cleanDesc.isNullOrEmpty()) {
                append(cleanDesc)
            }
            val cleanAlt = alternativeTitle?.trim()
            if (!cleanAlt.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Nome alternativo: ")
                append(cleanAlt)
            }
        }.takeIf(String::isNotEmpty)
        status = when (this@MangaDto.status?.trim()?.lowercase()) {
            "ongoing", "upcoming", "em lançamento", "em breve" -> SManga.ONGOING
            "completed", "completo" -> SManga.COMPLETED
            "hiatus", "on hold", "em hiato", "em espera" -> SManga.ON_HIATUS
            "canceled", "cancelled", "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    fun toSChapterList(
        createChapter: () -> SChapter = SChapter::create,
    ): List<SChapter> {
        val slug = getSlug()
        return chapters?.map { it.toSChapter(slug, id, createChapter) } ?: emptyList()
    }

    internal fun getSlug(): String {
        val noDiacritics = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(MARKS_REGEX, "")
        return noDiacritics.lowercase()
            .replace(NON_ALPHA_REGEX, "-")
            .trim('-')
    }

    private fun List<String>?.cleanNames(): String? = this
        ?.map(String::trim)
        ?.filter { it.isNotEmpty() && !it.equals("N/A", ignoreCase = true) }
        ?.takeIf(List<String>::isNotEmpty)
        ?.joinToString()

    companion object {
        private val MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
        private val NON_ALPHA_REGEX = Regex("[^a-z0-9]+")
    }
}

@Serializable
class ChapterReferenceDto(
    val mangaId: String,
    val chapterId: String,
    val pageCount: Int? = null,
)

@Serializable
class ReaderAccessRequestDto(
    private val mangaId: String,
    private val chapterId: String,
)

@Serializable
class ReaderVerifiedAccessRequestDto(
    private val mangaId: String,
    private val chapterId: String,
    private val turnstileToken: String,
)

@Serializable
class ReaderAccessResponseDto(
    val chapter: ReaderChapterDto? = null,
    val protectedPages: Boolean = false,
    val firstHandle: HandleDto? = null,
    val grant: GrantDto? = null,
    val pageCount: Int? = null,
)

@Serializable
class ReaderChapterDto(
    val pages: List<String> = emptyList(),
    val pageCount: Int? = null,
)

@Serializable
class HandleDto(
    val token: String,
    val pageNumber: Int? = null,
    val pageCount: Int? = null,
)

@Serializable
class GrantDto(
    val token: String,
    val expiresAt: Long? = null,
)

@Serializable
class PageResponseDto(
    val imageUrl: String? = null,
    val protectedEdge: Boolean = false,
    val pageNumber: Int? = null,
    val pageCount: Int? = null,
    val nextHandle: HandleDto? = null,
)

@Serializable
class ReaderAccessErrorDto(
    val error: String? = null,
    val requiresTurnstile: Boolean = false,
    val interactivePending: Boolean = false,
)

@Serializable
class ChapterDto(
    val id: String,
    val number: String,
    private val title: String? = null,
    private val timestamp: Long = 0L,
    val pageCount: Int? = null,
) {
    fun toSChapter(
        slug: String,
        mangaId: String,
        createChapter: () -> SChapter = SChapter::create,
    ) = createChapter().apply {
        val cleanTitle = title?.trim()?.takeIf(String::isNotEmpty)
        name = if (cleanTitle != null) "Capítulo $number - $cleanTitle" else "Capítulo $number"
        chapter_number = number.toFloatOrNull() ?: -1f
        date_upload = timestamp
        url = "/$slug/$number"
        memo = buildJsonObject {
            put("mangaId", mangaId)
            put("chapterId", id)
            if (pageCount != null) {
                put("pageCount", pageCount)
            }
        }
    }
}
