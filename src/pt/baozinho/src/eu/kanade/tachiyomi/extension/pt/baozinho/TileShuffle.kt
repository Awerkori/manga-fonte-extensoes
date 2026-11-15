package eu.kanade.tachiyomi.extension.pt.baozinho

/**
 * Tile permutation applied by the reader, reproduced from the `semente` of the reading session.
 *
 * The seed is four 32-bit words read from the hex string, fed into the reader's xorshift generator
 * and used to shuffle the tile indices with Fisher-Yates.
 */
internal object TileShuffle {

    /** Tile indices in the order the reader draws them: tile [order][i] of the request holds tile i. */
    fun order(seed: String, size: Int): IntArray {
        val random = random(seed)
        val order = IntArray(size) { it }
        for (i in size - 1 downTo 1) {
            val j = (random() * (i + 1)).toInt()
            val swap = order[i]
            order[i] = order[j]
            order[j] = swap
        }
        return order
    }

    private fun random(seed: String): () -> Double {
        var t = seed.word(0)
        var r = seed.word(8)
        var o = seed.word(16)
        var n = seed.word(24)
        val next: () -> Double = {
            val sum = t + r + n
            n += 1
            t = r xor (r ushr 9)
            r = o + (o shl 3)
            o = ((o shl 21) or (o ushr 11)) + sum
            (sum.toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
        repeat(WARMUP) { next() }
        return next
    }

    private fun String.word(offset: Int): Int = substring(offset, offset + 8).toLong(16).toInt()

    private const val WARMUP = 12
}
