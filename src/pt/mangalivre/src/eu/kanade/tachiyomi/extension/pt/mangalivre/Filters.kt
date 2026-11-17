package eu.kanade.tachiyomi.extension.pt.mangalivre

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(
    title: String,
    private val options: Array<Pair<String, String>>,
    state: Int = 0,
) : Filter.Select<String>(title, options.map { it.first }.toTypedArray(), state) {
    val selected: String get() = options[state].second
}

class OrderByFilter(
    title: String,
    options: Array<Pair<String, String>>,
    state: Int = 0,
) : SelectFilter(title, options, state)

class OrderDirectionFilter(
    title: String,
    options: Array<Pair<String, String>>,
    state: Int = 0,
) : SelectFilter(title, options, state)

class CheckBoxOption(name: String, val value: String) : Filter.CheckBox(name)

class TypeFilter(options: List<CheckBoxOption>) : Filter.Group<CheckBoxOption>("Tipo", options)

class StatusFilter(options: List<CheckBoxOption>) : Filter.Group<CheckBoxOption>("Status", options)
