package eu.kanade.tachiyomi.extension.pt.baozinho

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TileShuffleTest {

    // Values captured from the reader's own script for the seed and tile count of a real page.
    private val seed = "3d2c6934750cbe7ff3d64ba0f018ea30"
    private val tiles = 876

    @Test
    fun `matches the reader permutation`() {
        val order = TileShuffle.order(seed, tiles)

        assertEquals(tiles, order.toSet().size)
        assertArrayEquals(intArrayOf(728, 787, 413, 586, 187, 16, 661, 512, 609, 337), order.copyOfRange(0, 10))
        assertArrayEquals(intArrayOf(292, 239, 163, 536, 400), order.copyOfRange(871, 876))
    }
}
