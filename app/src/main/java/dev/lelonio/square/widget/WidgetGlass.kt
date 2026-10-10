package dev.lelonio.square.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

/**
 * The widget's glass, drawn as a picture.
 *
 * A widget is drawn by the launcher from a description, and nothing in that
 * description can blur or bend what lies behind it: the app's own glass cannot
 * be had on a home screen. What can is the look of it over the record playing:
 * the cover, blurred and dimmed, under a sheen and a rim of light, which is
 * what the player's glass looks like when it sits on the artwork.
 */
internal object WidgetGlass {
    /** The panel, [width] by [height] pixels, with [radius] corners. */
    fun panel(cover: Bitmap?, width: Int, height: Int, radius: Float, density: Float): Bitmap {
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val shape = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
        canvas.clipPath(shape)

        if (cover != null) {
            canvas.drawBitmap(blurred(cover, width, height), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawColor(Color.argb(105, 0, 0, 0))
        } else {
            canvas.drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                    Color.rgb(0x34, 0x34, 0x3A), Color.rgb(0x16, 0x16, 0x1A), Shader.TileMode.CLAMP)
            })
        }

        // The sheen across the top, where light would catch the glass.
        canvas.drawRect(bounds, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height * 0.55f,
                Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        })

        // The rim: bright at the top left, fading round to the bottom right.
        val stroke = 1.4f * density
        val inset = stroke / 2
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset), radius - inset, radius - inset,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                    Color.argb(120, 255, 255, 255), Color.argb(18, 255, 255, 255), Shader.TileMode.CLAMP)
            },
        )
        return out
    }

    /** A cover with rounded corners, for the views that cannot clip. */
    fun rounded(source: Bitmap, size: Int, radius: Float): Bitmap {
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(centreCrop(source, size, size), 0f, 0f, paint)
        return out
    }

    /**
     * A playlist with no cover of its own: a pane of the same glass with its
     * name on it, rather than an empty square nobody could tell apart.
     */
    fun nameTile(name: String, size: Int, radius: Float, density: Float): Bitmap {
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                Color.argb(70, 255, 255, 255), Color.argb(30, 255, 255, 255), Shader.TileMode.CLAMP)
        })
        val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val pad = (6 * density).toInt()
        val layout = android.text.StaticLayout.Builder.obtain(name, 0, name.length, text, size - pad * 2)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(pad.toFloat(), (size - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        return out
    }

    /**
     * A real blur, on a small copy, grown back smoothly.
     *
     * Shrinking and growing alone was tried first, and from that few pixels
     * the growing shows them: the glass came out in blocks and steps. Here the
     * small copy is blurred properly first (a stack blur, which is a close and
     * quick stand-in for a Gaussian), so what is grown back has no edges left
     * to show.
     */
    private fun blurred(source: Bitmap, width: Int, height: Int): Bitmap {
        val small = (BLUR_WIDTH.toFloat() / width).coerceAtMost(1f)
        val w = (width * small).toInt().coerceAtLeast(8)
        val h = (height * small).toInt().coerceAtLeast(8)
        val copy = centreCrop(source, w, h).copy(Bitmap.Config.ARGB_8888, true)
        stackBlur(copy, BLUR_RADIUS)
        return copy.scale(width, height, filter = true)
    }

    /** Mario Klingemann's stack blur, in place, on the colour channels. */
    private fun stackBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val div = radius * 2 + 1
        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)
        val dv = IntArray(256 * ((div + 1) / 2) * ((div + 1) / 2)) { it / (((div + 1) / 2) * ((div + 1) / 2)) }
        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1
        val vmin = IntArray(maxOf(w, h))

        var yi = 0
        var yw = 0
        for (y in 0 until h) {
            var rin = 0; var gin = 0; var bin = 0; var rout = 0; var gout = 0; var bout = 0
            var rsum = 0; var gsum = 0; var bsum = 0
            for (i in -radius..radius) {
                val p = pixels[yi + minOf(w - 1, maxOf(i, 0))]
                val sir = stack[i + radius]
                sir[0] = p shr 16 and 0xff; sir[1] = p shr 8 and 0xff; sir[2] = p and 0xff
                val rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs
                if (i > 0) { rin += sir[0]; gin += sir[1]; bin += sir[2] } else { rout += sir[0]; gout += sir[1]; bout += sir[2] }
            }
            var stackpointer = radius
            for (x in 0 until w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
                rsum -= rout; gsum -= gout; bsum -= bout
                var sir = stack[(stackpointer - radius + div) % div]
                rout -= sir[0]; gout -= sir[1]; bout -= sir[2]
                if (y == 0) vmin[x] = minOf(x + radius + 1, w - 1)
                val p = pixels[yw + vmin[x]]
                sir[0] = p shr 16 and 0xff; sir[1] = p shr 8 and 0xff; sir[2] = p and 0xff
                rin += sir[0]; gin += sir[1]; bin += sir[2]
                rsum += rin; gsum += gin; bsum += bin
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                rout += sir[0]; gout += sir[1]; bout += sir[2]
                rin -= sir[0]; gin -= sir[1]; bin -= sir[2]
                yi++
            }
            yw += w
        }
        for (x in 0 until w) {
            var rin = 0; var gin = 0; var bin = 0; var rout = 0; var gout = 0; var bout = 0
            var rsum = 0; var gsum = 0; var bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                yi = maxOf(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                val rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs
                if (i > 0) { rin += sir[0]; gin += sir[1]; bin += sir[2] } else { rout += sir[0]; gout += sir[1]; bout += sir[2] }
                if (i < h - 1) yp += w
            }
            yi = x
            var stackpointer = radius
            for (y in 0 until h) {
                pixels[yi] = (0xff000000).toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= rout; gsum -= gout; bsum -= bout
                var sir = stack[(stackpointer - radius + div) % div]
                rout -= sir[0]; gout -= sir[1]; bout -= sir[2]
                if (x == 0) vmin[y] = minOf(y + r1, h - 1) * w
                val p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rin += sir[0]; gin += sir[1]; bin += sir[2]
                rsum += rin; gsum += gin; bsum += bin
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                rout += sir[0]; gout += sir[1]; bout += sir[2]
                rin -= sir[0]; gin -= sir[1]; bin -= sir[2]
                yi += w
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** How wide the copy that is blurred is, and how far it is blurred. */
    private const val BLUR_WIDTH = 160
    private const val BLUR_RADIUS = 14

    private fun centreCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = (width / scale).toInt().coerceIn(1, source.width)
        val h = (height / scale).toInt().coerceIn(1, source.height)
        val x = (source.width - w) / 2
        val y = (source.height - h) / 2
        return Bitmap.createBitmap(source, x, y, w, h).scale(width, height, filter = true)
    }
}
