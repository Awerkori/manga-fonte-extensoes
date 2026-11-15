package eu.kanade.tachiyomi.extension.pt.baozinho

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import keiyoushi.lib.imagedecoder.ImageDecoder
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Reassembles the 64 px tile grid the reader shuffles on every page image.
 *
 * The permutation comes from the `semente` of the reading session, carried in the URL fragment
 * (never sent to the server) as `#s=<semente>`.
 */
class ScrambleInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val seed = request.url.fragment
            ?.takeIf { it.startsWith(SEED_PREFIX) }
            ?.removePrefix(SEED_PREFIX)
            ?: return chain.proceed(request)

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        response.use {
            val bytes = response.body.bytes()
            val scrambled = ImageDecoder.decode(bytes)
            val columns = scrambled.width / TILE
            val tiles = columns * (scrambled.height / TILE)
            if (tiles < 2) {
                return response.newBuilder()
                    .body(bytes.toResponseBody(response.body.contentType()))
                    .build()
            }

            val order = TileShuffle.order(seed, tiles)
            val output = Bitmap.createBitmap(scrambled.width, scrambled.height, Bitmap.Config.ARGB_8888)
            AutoCloseable { scrambled.recycle() }.use {
                val canvas = Canvas(output)
                // Only whole 64 px rows/columns are shuffled, so the leftovers on the edges stay as they are.
                canvas.drawBitmap(scrambled, 0f, 0f, null)
                val src = Rect()
                val dst = Rect()
                for (i in 0 until tiles) {
                    val to = order[i]
                    val srcX = i % columns * TILE
                    val srcY = i / columns * TILE
                    val dstX = to % columns * TILE
                    val dstY = to / columns * TILE
                    src.set(srcX, srcY, srcX + TILE, srcY + TILE)
                    dst.set(dstX, dstY, dstX + TILE, dstY + TILE)
                    canvas.drawBitmap(scrambled, src, dst, null)
                }
            }

            val buffer = Buffer()
            AutoCloseable { output.recycle() }.use {
                if (!output.compress(WEBP_FORMAT, 90, buffer.outputStream())) {
                    throw IOException("Could not encode the unscrambled image")
                }
            }

            return response.newBuilder()
                .header("Content-Type", WEBP_MEDIA_TYPE.toString())
                .header("Content-Length", buffer.size.toString())
                .body(buffer.asResponseBody(WEBP_MEDIA_TYPE, buffer.size))
                .build()
        }
    }

    companion object {
        private const val TILE = 64
        private const val SEED_PREFIX = "s="

        @Suppress("DEPRECATION")
        private val WEBP_FORMAT = Bitmap.CompressFormat.WEBP
        private val WEBP_MEDIA_TYPE = "image/webp".toMediaType()
    }
}
