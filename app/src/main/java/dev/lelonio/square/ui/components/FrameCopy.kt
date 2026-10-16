package dev.lelonio.square.ui.components

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A small copy of what a video's [TextureView] is showing, read without
 * stopping the screen.
 *
 * `TextureView.getBitmap` is the obvious way, and the costly one: it runs on
 * the main thread and waits there for the render thread and the GPU to finish
 * whatever they are doing before it copies the layer. Measured on the player
 * with a moving cover, that was 27 milliseconds a copy, ten copies a second, a
 * frame lost every time, and more than a fifth of the main thread gone.
 *
 * This asks for the last frame the video queued instead, through PixelCopy,
 * which answers on a thread of its own. Nothing on the main thread waits for
 * it. The surface the video draws into is the same one the view shows, so the
 * picture is the same; scaled to [width] by [height] as it is copied, as
 * getBitmap scaled it.
 *
 * Null when there is nothing to copy yet, or the copy failed.
 */
internal suspend fun TextureView.copyFrame(width: Int, height: Int): Bitmap? {
    val texture = surfaceTexture ?: return null
    if (width <= 0 || height <= 0) return null
    return suspendCancellableCoroutine { done ->
        // Asked from the copy thread as well as answered there. Before Android
        // 14 the request itself waits for the render thread to make the copy,
        // and made from the main thread that wait was the main thread's again.
        copies.post {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // A second handle on the same queue, not a second queue: it is only
            // read from, and releasing it leaves the video's own connection
            // alone. Released once the copy is made, never while it is pending.
            val surface = runCatching { Surface(texture) }.getOrNull()
            if (surface == null) {
                bitmap.recycle()
                done.resume(null)
                return@post
            }
            runCatching {
                PixelCopy.request(surface, bitmap, { result ->
                    surface.release()
                    if (result == PixelCopy.SUCCESS) {
                        done.resume(bitmap)
                    } else {
                        bitmap.recycle()
                        done.resume(null)
                    }
                }, copies)
            }.onFailure {
                surface.release()
                bitmap.recycle()
                done.resume(null)
            }
        }
    }
}

/** Where the copies are answered: off the main thread, and one at a time. */
private val copies: Handler by lazy {
    Handler(HandlerThread("SquareFrameCopy").apply { start() }.looper)
}
