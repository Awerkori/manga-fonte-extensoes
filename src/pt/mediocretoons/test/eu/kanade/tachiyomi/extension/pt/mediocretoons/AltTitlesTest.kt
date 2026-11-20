package eu.kanade.tachiyomi.extension.pt.mediocretoons

import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

// The API returns `obr_titulos_alternativos` in several shapes; each one below was seen on /obras/{id}.
class AltTitlesTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }

    private fun parse(json: String) = parseAltTitles(Json.parseToJsonElement(json))

    // The site serialized the list to a string `depth - 1` extra times before sending it.
    private fun nested(depth: Int, titles: List<String>): String {
        var element = JsonArray(titles.map(::JsonPrimitive)).toString()
        repeat(depth - 1) { element = JsonArray(listOf(JsonPrimitive(element))).toString() }
        return JsonArray(listOf(JsonPrimitive(element))).toString()
    }

    @Test
    fun `a plain array is read as it is`() {
        assertEquals(
            listOf("Revenge of the Hound of the Baskervilles", "Revenge of the Baskerville Bloodhound"),
            parse("""["Revenge of the Hound of the Baskervilles","Revenge of the Baskerville Bloodhound"]"""),
        )
    }

    @Test
    fun `json serialized once inside a string is unwrapped`() {
        assertEquals(listOf("Martial Peak"), parse(nested(1, listOf("Martial Peak"))))
        assertEquals(
            listOf("The Stellar Swordmaster", "Byeoreul Pumeun Swordmaster", "Star-Embracing Swordmaster"),
            parse(nested(1, listOf("The Stellar Swordmaster", "Byeoreul Pumeun Swordmaster", "Star-Embracing Swordmaster"))),
        )
    }

    @Test
    fun `json serialized twice keeps non latin titles`() {
        val titles = listOf("Tales of Demons and Gods", "妖神记", "TODAG", "Tales of Demons & Gods", "Yao Shen Ji")

        assertEquals(titles, parse(nested(2, titles)))
    }

    @Test
    fun `any nesting depth ends in the titles`() {
        assertEquals(listOf("Deep Title"), parse(nested(12, listOf("Deep Title"))))
    }

    @Test
    fun `the null placeholder is dropped at every depth`() {
        assertEquals(emptyList<String>(), parse("""["null"]"""))
        assertEquals(emptyList<String>(), parse(nested(1, listOf("null"))))
        assertEquals(emptyList<String>(), parse(nested(3, listOf("null"))))
    }

    @Test
    fun `plain and serialized titles can come together`() {
        assertEquals(
            listOf("Secund Life Ranker", "Second Life Ranker"),
            parse("""["[\"Secund Life Ranker\"]","Second Life Ranker"]"""),
        )
    }

    @Test
    fun `a comma inside a title does not split it`() {
        assertEquals(listOf("Pick me Up, Infinite Gacha"), parse("""["Pick me Up, Infinite Gacha"]"""))
    }

    @Test
    fun `text that only looks like json stays a title`() {
        assertEquals(listOf("[Remake] Title"), parse("""["[Remake] Title"]"""))
        assertEquals(listOf("86"), parse("""["86"]"""))
        assertEquals(listOf("\"Quoted"), parse("""["\"Quoted"]"""))
    }

    @Test
    fun `blanks are dropped and surrounding spaces trimmed`() {
        assertEquals(listOf("Disastrous Necromancer"), parse("""["", "  ", " Disastrous Necromancer"]"""))
    }

    @Test
    fun `a single string and a missing value are handled`() {
        assertEquals(listOf("Solo"), parse("\"Solo\""))
        assertEquals(emptyList<String>(), parseAltTitles(null))
        assertEquals(emptyList<String>(), parse("null"))
        assertEquals(emptyList<String>(), parse("[]"))
    }

    // Real /obras/{id} responses collected on 2026-11-20 (55 works across the whole catalog).
    private val real = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/alt_titles_real.json")).readText()).let { it as kotlinx.serialization.json.JsonObject }

    @Test
    fun `real responses give the expected titles`() {
        val expected = mapOf(
            "183" to listOf("Revenge of the Hound of the Baskervilles", "Revenge of the Baskerville Bloodhound"),
            "361" to listOf("Martial Peak"),
            "6" to listOf("The Stellar Swordmaster", "Byeoreul Pumeun Swordmaster", "Star-Embracing Swordmaster"),
            "19007" to listOf("Tales of Demons and Gods", "妖神记", "TODAG", "Tales of Demons & Gods", "Yao Shen Ji"),
            "60" to emptyList(),
            "19568" to emptyList(),
            "216" to listOf("Secund Life Ranker", "Second Life Ranker"),
            "217" to listOf("Pick me Up", "픽 미 업!", "Escolha-me! Gacha Infinito", "Me Escolha! Gacha Infinito", "Pick me Up, Infinite Gacha"),
            "14" to listOf("Catastrophic Necromancer", "Disastrous Necromancer", "Necromante: Rei dos Mortos", "Necromante Desastroso", "Necromante! Eu Sou Um Desastre"),
        )

        expected.forEach { (id, titles) -> assertEquals("obra $id", titles, parseAltTitles(real.getValue(id))) }
    }

    @Test
    fun `no real response leaves json leftovers or placeholders in a title`() {
        real.forEach { (id, raw) ->
            parseAltTitles(raw).forEach { title ->
                assertTrue("obra $id: '$title'", title == title.trim() && title.isNotEmpty() && !title.startsWith("[\"") && !title.contains("\\\"") && !title.equals("null", ignoreCase = true))
            }
        }
    }

    private fun details(altTitles: String?, description: String? = "Sinopse") = """
        {"obr_id":361,"obr_nome":"Pico Marcial","obr_descricao":${description?.let { "\"$it\"" }},
         ${altTitles?.let { "\"obr_titulos_alternativos\":$it," }.orEmpty()}"capitulos":[]}
    """.parseAs<MangaDetailsDto>()

    @Test
    fun `the description lists the alternative titles`() {
        val markdown = details(nested(1, listOf("Martial Peak", "Wulian Dianfeng"))).descriptionMarkdown()!!

        assertTrue(markdown.startsWith("Sinopse"))
        assertTrue(markdown.contains("- Martial Peak"))
        assertTrue(markdown.contains("- Wulian Dianfeng"))
    }

    @Test
    fun `without alternative titles the description is only the synopsis`() {
        assertEquals("Sinopse", details(null).descriptionMarkdown())
        assertEquals("Sinopse", details("""["null"]""").descriptionMarkdown())
    }

    @Test
    fun `no synopsis and no titles gives no description`() {
        assertNull(details(null, description = null).descriptionMarkdown())
    }
}
