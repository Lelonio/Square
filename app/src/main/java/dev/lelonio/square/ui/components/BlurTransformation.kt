package dev.lelonio.square.ui.components

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Blurs an image while it is being decoded.
 *
 * The page backdrop went through two wrong versions before this one. First
 * `Modifier.blur` at 80dp: correct-looking, but a RuntimeEffect over the whole
 * window re-run on every frame the layer changed, which is most of what made
 * expanding the player stutter. Then a 32px bitmap stretched to the window,
 * which costs nothing and looks it — bilinear upscaling from that size leaves
 * visible square blocks rather than a wash.
 *
 * Blurring at decode time is the version that is both: the work happens once
 * per cover, on a small bitmap, and Coil caches the result. What reaches the
 * screen is genuinely smooth, and drawing it is an ordinary image draw.
 *
 * A box blur run twice rather than a gaussian: two box passes approximate a
 * gaussian closely enough that nobody can tell on an image that is about to be
 * stretched thirty times, and it is a handful of adds per pixel.
 *
 * @param radius in pixels of the *decoded* image, not of the screen.
 */
class BlurTransformation(private val radius: Int = 12, private val passes: Int = 2) :
    Transformation {

    override val cacheKey: String = "${javaClass.name}-$radius-$passes"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width <= 1 || height <= 1) return input

        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)

        val scratch = IntArray(width * height)
        repeat(passes) {
            boxBlurHorizontal(pixels, scratch, width, height, radius)
            boxBlurVertical(scratch, pixels, width, height, radius)
        }

        // ARGB_8888 rather than the input's config: a hardware bitmap cannot be
        // read back, and Coil hands those out unless told otherwise.
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlurHorizontal(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                // Clamped at the edges, which repeats the border pixel. On an
                // image this blurred, the alternative — wrapping — would drag
                // one side's colour onto the other.
                for (offset in -radius..radius) {
                    val sample = source[row + (x + offset).coerceIn(0, width - 1)]
                    a += (sample ushr 24) and 0xFF
                    r += (sample ushr 16) and 0xFF
                    g += (sample ushr 8) and 0xFF
                    b += sample and 0xFF
                    count++
                }
                target[row + x] = pack(a / count, r / count, g / count, b / count)
            }
        }
    }

    private fun boxBlurVertical(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        for (x in 0 until width) {
            for (y in 0 until height) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (offset in -radius..radius) {
                    val sample = source[(y + offset).coerceIn(0, height - 1) * width + x]
                    a += (sample ushr 24) and 0xFF
                    r += (sample ushr 16) and 0xFF
                    g += (sample ushr 8) and 0xFF
                    b += sample and 0xFF
                    count++
                }
                target[y * width + x] = pack(a / count, r / count, g / count, b / count)
            }
        }
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    override fun equals(other: Any?): Boolean =
        other is BlurTransformation && other.radius == radius && other.passes == passes

    override fun hashCode(): Int = cacheKey.hashCode()
}
