package eu.kanade.tachiyomi.extension.pt.reinomanga

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl

fun getFilters(genres: Map<String, String>): FilterList = buildList {
    add(SortFilter())
    add(StatusFilter())
    if (genres.isNotEmpty()) {
        add(GenreFilter(genres))
    }
}.let(::FilterList)

interface QueryFilter {
    fun applyTo(url: HttpUrl.Builder)
}

open class SelectFilter(
    name: String,
    private val param: String,
    private val options: List<Pair<String, String>>,
    defaultValue: String? = null,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
    options.indexOfFirst { it.second == defaultValue }.coerceAtLeast(0),
),
    QueryFilter {
    override fun applyTo(url: HttpUrl.Builder) {
        val value = options[state].second
        if (value.isNotEmpty()) {
            url.addQueryParameter(param, value)
        }
    }
}

class SortFilter(defaultOrder: String? = null) :
    SelectFilter(
        "Ordenar por",
        "ordem",
        listOf(
            "Destaques" to "featured",
            "Atualizados" to "updated-desc",
            "Título (A–Z)" to "title-asc",
            "Ano (mais recente)" to "year-desc",
        ),
        defaultOrder,
    )

class StatusFilter(defaultStatus: String? = null) :
    SelectFilter(
        "Status",
        "status",
        listOf(
            "Todos" to "",
            "Em andamento" to "Em andamento",
            "Completo" to "Completo",
            "Hiato" to "Hiato",
        ),
        defaultStatus,
    )

class GenreFilter(
    genres: Map<String, String>,
    defaultGenre: String? = null,
) : SelectFilter(
    "Gênero",
    "genero",
    buildList {
        add("Todos" to "")
        genres.forEach { (name, slug) -> add(name to slug) }
    },
    defaultGenre,
)
