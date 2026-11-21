package eu.kanade.tachiyomi.extension.pt.mangastop

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

internal open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected get() = options[state].second
}

internal class SortFilter :
    SelectFilter(
        "Ordenar por",
        listOf(
            "Populares" to "mais-populares",
            "Recentes" to "recentes",
            "Mais favoritadas" to "mais-favoritadas",
        ),
    )

internal class TypeFilter :
    SelectFilter(
        "Tipo",
        listOf(
            "Todos" to "",
            "Mangá" to "Manga",
            "Manhwa" to "Manhwa",
            "Manhua" to "Manhua",
            "Comic" to "Comic",
            "Pornhwa" to "Pornhwa",
        ),
    )

internal class GenreFilter(genres: List<GenreDto>) :
    SelectFilter(
        "Gênero",
        listOf("Todos" to "") + genres.map { it.name to it.slug },
    )

internal fun buildMangaStopFilterList(data: JsonElement?): FilterList {
    val genres = data?.takeUnless { it is JsonNull }?.parseAs<List<GenreDto>>()
    return FilterList(
        buildList<Filter<*>> {
            add(Filter.Header("Os filtros não se aplicam à busca por texto"))
            add(SortFilter())
            add(TypeFilter())
            if (genres != null) {
                add(Filter.Header("Com um gênero selecionado, a ordenação é ignorada"))
                add(GenreFilter(genres))
            }
        },
    )
}
