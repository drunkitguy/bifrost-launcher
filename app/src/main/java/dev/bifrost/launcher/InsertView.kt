package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/**
 * The launch animation: the case comes forward, the disc or cartridge slides out of it and drops
 * into the console. On the Thor the two halves play on two screens: [Mode.TOP] ends with the disc
 * falling off the bottom edge, and [Mode.BOTTOM] catches it into the console.
 */
internal class InsertView(context: Context) : View(context) {
    enum class Mode { SINGLE, TOP, BOTTOM }

    private var mode = Mode.SINGLE
    private var look = Look.of("")
    private var case: Bitmap? = null
    private var media: Bitmap? = null
    private var from = RectF()
    private var start = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    val running get() = start != 0L

    init {
        visibility = INVISIBLE // keeps its size, so the other screen can start it at once
        isClickable = true // nothing underneath reacts mid-animation
    }

    /** [from] is where the case starts, in this view's coordinates (ignored for [Mode.BOTTOM]). */
    fun play(game: Library.Game, mode: Mode, from: RectF?) {
        val (caseBitmap, mediaBitmap) = Faces.forInsert(context, game, (height * 0.55f).toInt().coerceAtLeast(64))
        this.mode = mode
        look = Look.of(game.system)
        case = caseBitmap
        media = mediaBitmap
        this.from = from ?: RectF(width / 2f, height / 2f, width / 2f, height / 2f)
        start = SystemClock.uptimeMillis()
        visibility = VISIBLE
        invalidate()
    }

    fun stop() {
        start = 0L
        visibility = INVISIBLE
        case = null
        media = null
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val t = (SystemClock.uptimeMillis() - start).toFloat()
        if (t > TOTAL + 400) return stop()
        val w = width.toFloat()
        val h = height.toFloat()

        paint.color = (min(1f, t / 300f) * 0xD8).toInt() shl 24
        canvas.drawRect(0f, 0f, w, h, paint)

        val case = case ?: return
        val media = media ?: return
        val caseH = h * if (mode == Mode.SINGLE) 0.42f else 0.6f
        val caseW = caseH * case.width / case.height
        // The case comes to the stage, then steps left to let the media out.
        val stage = RectF(w * 0.5f - caseW / 2, h * 0.1f, w * 0.5f + caseW / 2, h * 0.1f + caseH)
        val aside = ease(t, 300f, 650f) * caseW * 0.38f
        val caseRect = lerp(from, stage, ease(t, 0f, 300f)).apply { offset(-aside, 0f) }

        val mediaSize = caseH * 0.62f * media.width / maxOf(media.width, media.height)
        val mediaH = mediaSize * media.height / media.width
        val inCase = caseRect.centerX()
        val outX = caseRect.centerX() + caseW * 0.72f
        // Console: rises from the bottom while the case opens.
        val consoleW = min(w * 0.6f, h * 0.9f)
        val consoleBox = RectF(w / 2 - consoleW / 2, h * 0.58f, w / 2 + consoleW / 2, h * 0.96f)
        consoleBox.offset(0f, (1 - ease(t, 0f, 650f)) * h * 0.5f)
        var slot: RectF? = null
        if (mode != Mode.TOP) {
            slot = Render.console(canvas, look, consoleBox, ease(t, 1250f, 1450f))
        }
        val spin = if (look.media == Look.Media.DISC || look.media == Look.Media.MINI_DISC) t * 0.5f else 0f

        // Where the media is and whether it is still behind the case.
        var behindCase = false
        var mx: Float
        var my: Float
        when {
            t < 650f && mode != Mode.BOTTOM -> {
                mx = inCase + (outX - inCase) * ease(t, 300f, 650f)
                my = caseRect.centerY()
                behindCase = true
            }
            mode == Mode.TOP -> {
                val k = ease(t, 650f, 1000f)
                mx = outX + (w / 2 - outX) * k
                my = caseRect.centerY() + (h + mediaH - caseRect.centerY()) * k
            }
            else -> {
                val s = slot ?: RectF(w / 2, h, w / 2, h)
                val startX = if (mode == Mode.BOTTOM) w / 2 else outX
                val startY = if (mode == Mode.BOTTOM) -mediaH else caseRect.centerY()
                val above = s.top - mediaH * 0.55f
                val travel = ease(t, 650f, 1100f)
                mx = startX + (s.centerX() - startX) * travel
                my = startY + (above - startY) * travel
                my += ease(t, 1100f, 1400f) * mediaH // into the slot
            }
        }
        if (mode == Mode.BOTTOM && t < 650f) my = -mediaH * 2 // still on the top screen

        if (behindCase) drawMedia(canvas, media, mx, my, mediaSize, spin, null)
        if (mode != Mode.BOTTOM) {
            paint.alpha = if (mode == Mode.SINGLE) (255 * (1 - ease(t, 900f, 1200f))).toInt() else 255
            canvas.drawBitmap(case, null, caseRect, paint)
            paint.alpha = 255
        }
        if (!behindCase) drawMedia(canvas, media, mx, my, mediaSize, spin, slot)

        postInvalidateOnAnimation()
    }

    /** Media centered at ([x], [y]); anything below the slot is inside the console, so clipped. */
    private fun drawMedia(c: Canvas, media: Bitmap, x: Float, y: Float, size: Float, spin: Float, slot: RectF?) {
        c.save()
        if (slot != null) c.clipRect(0f, 0f, width.toFloat(), slot.top)
        val scale = size / media.width
        matrix.reset()
        matrix.postTranslate(-media.width / 2f, -media.height / 2f)
        matrix.postRotate(spin)
        matrix.postScale(scale, scale)
        matrix.postTranslate(x, y)
        c.drawBitmap(media, matrix, paint)
        c.restore()
    }

    private fun ease(t: Float, from: Float, to: Float): Float {
        val k = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return k * k * (3 - 2 * k)
    }

    private fun lerp(a: RectF, b: RectF, k: Float) = RectF(
        a.left + (b.left - a.left) * k, a.top + (b.top - a.top) * k,
        a.right + (b.right - a.right) * k, a.bottom + (b.bottom - a.bottom) * k,
    )

    companion object {
        /** When the game starts, in ms after the animation begins. */
        const val TOTAL = 1500L
    }
}
