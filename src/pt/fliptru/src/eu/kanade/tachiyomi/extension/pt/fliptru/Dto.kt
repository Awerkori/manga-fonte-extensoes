package eu.kanade.tachiyomi.extension.pt.fliptru

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ComicSeriesLd(
    @SerialName("@type") val type: String = "",
    val name: String = "",
    val description: String = "",
    val image: String = "",
    val genre: String = "",
    val author: AuthorLd? = null,
)

@Serializable
class AuthorLd(
    val name: String = "",
)

@Serializable
class SearchResultDto(
    val label: String,
    val url: String,
)
