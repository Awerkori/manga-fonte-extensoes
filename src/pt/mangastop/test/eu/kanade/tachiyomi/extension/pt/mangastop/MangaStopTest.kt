package eu.kanade.tachiyomi.extension.pt.mangastop

import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class MangaStopTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            Injekt.addSingleton<Json>(Json { ignoreUnknownKeys = true })
        }
    }

    @Test
    fun buildsFilterListSafelyWhenCacheContainsStaleJsonNull() {
        val filters = buildMangaStopFilterList(JsonNull)

        assertNotNull(filters.firstInstanceOrNull<SortFilter>())
        assertNotNull(filters.firstInstanceOrNull<TypeFilter>())
        assertNull(filters.firstInstanceOrNull<GenreFilter>())
    }

    @Test
    fun buildsFilterListSafelyWhenCacheIsNull() {
        val filters = buildMangaStopFilterList(null)

        assertNotNull(filters.firstInstanceOrNull<SortFilter>())
        assertNotNull(filters.firstInstanceOrNull<TypeFilter>())
        assertNull(filters.firstInstanceOrNull<GenreFilter>())
    }

    @Test
    fun includesGenresWhenValidWpJsonArrayIsCached() {
        val jsonText = checkNotNull(javaClass.getResourceAsStream("/genres.json")).bufferedReader().use { it.readText() }
        val element = Json.parseToJsonElement(jsonText)

        val filters = buildMangaStopFilterList(element)
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()

        assertNotNull(genreFilter)
        assertTrue(genreFilter!!.values.toList().containsAll(listOf("Todos", "Ação", "Aventura", "Fantasia")))
    }

    @Test
    fun parsesGenreDtosFromSanitizedWpRestFixture() {
        val jsonText = checkNotNull(javaClass.getResourceAsStream("/genres.json")).bufferedReader().use { it.readText() }
        val genres = jsonText.parseAs<List<GenreDto>>()

        assertEquals(listOf("acao", "aventura", "fantasia"), genres.map { it.slug })
    }
}
