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
    private val gratuito: Boolean = true,
    private val precoEmBaos: Int = 0,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        name = this@ChapterDto.nome
        chapter_number = numero
        date_upload = Instant.tryParse(lancadoEm)
        url = "capitulo-${numero.toString().removeSuffix(".0")}"
        memo = buildJsonObject {
            put("slug", slug)
            put("capId", id)
            put("gratuito", gratuito)
            put("precoEmBaos", precoEmBaos)
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
class ApiErrorDto(
    val desafio: String? = null,
    val message: String? = null,
)

@Serializable
class LoginRequest(
    private val email: String,
    private val senha: String,
    private val turnstileToken: String,
)

@Serializable
class RefreshRequest(
    private val refreshToken: String,
)

@Serializable
class AuthResponseDto(
    val accessToken: String,
    val refreshToken: String,
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
