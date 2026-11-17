package eu.kanade.tachiyomi.extension.pt.baozinho

import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

@Serializable
class ChapterDto(
    private val id: Long,
    private val nome: String,
    private val numero: Float,
    private val lancadoEm: String,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        name = this@ChapterDto.nome
        chapter_number = numero
        date_upload = Instant.tryParse(lancadoEm)
        url = "capitulo-${numero.toString().removeSuffix(".0")}"
        memo = buildJsonObject {
            put("slug", slug)
            put("capId", id)
        }
    }
}

@Serializable
class SinaisDto(
    private val webdriver: Boolean = false,
    private val headless: Boolean = false,
)

@Serializable
class SessionRequest(
    private val capId: Long,
    private val largura: Int = 1080,
    private val sinais: SinaisDto = SinaisDto(),
    private val turnstileToken: String? = null,
)

@Serializable
class ChallengeDto(
    val desafio: String? = null,
)

@Serializable
class SessionDto(
    val sid: String,
    val total: Int,
    val paginas: List<PageDto>,
    val proximo: Int,
    val intervaloMs: Long = 1000L,
)

@Serializable
class PageDto(
    val ordem: Int,
    val url: String,
    val semente: String? = null,
)
