package dev.bifrost.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The home row: one big rounded tile per console, its console art and logo on the console's
 * colors, the selected one raised with a glowing ring. A opens it.
 */
internal class HomeCarousel(context: Context) : View(context) {
    var onSelect: (Library.Shelf) -> Unit = {}
    var onEnter: (Library.Shelf) -> Unit = {}
    var onMenu: (Library.Shelf) -> Unit = {}

    var shelves: List<Library.Shelf> = emptyList()
        set(value) {
            field = value
            index = value.indexOfFirst { it.id == Library.selected }.coerceAtLeast(0)
            invalidate()
        }

    val current: Library.Shelf? get() = shelves.getOrNull(index)

    private var index = 0
    private var scroll = 0f
    private var lastFrame = 0L
    private var dragging = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var selectedRect = RectF()

    init {
        isFocusable = true
    }

    private fun tile() = min(height * 0.7f, width * 0.36f)
    private fun step() = tile() * 1.06f

    fun select(i: Int) {
        val next = i.coerceIn(0, (shelves.size - 1).coerceAtLeast(0))
        if (next == index || shelves.isEmpty()) return
        playSoundEffect(if (next < index) SoundEffectConstants.NAVIGATION_LEFT else SoundEffectConstants.NAVIGATION_RIGHT)
        index = next
        onSelect(shelves[index])
        invalidate()
    }

    fun enter() {
        playSoundEffect(SoundEffectConstants.CLICK)
        current?.let(onEnter)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> (index > 0).also { if (it) select(index - 1) }
        KeyEvent.KEYCODE_DPAD_RIGHT -> (index < shelves.size - 1).also { if (it) select(index + 1) }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true.also { enter() }
        else -> super.onKeyDown(keyCode, event)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            dragging = true
            scroll = (scroll + dx / step()).coerceIn(0f, (shelves.size - 1).toFloat().coerceAtLeast(0f))
            select(scroll.roundToInt())
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selectedRect.contains(e.x, e.y)) enter()
            else select((scroll + (e.x - width / 2f) / step()).roundToInt())
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            current?.let(onMenu)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            dragging = false
            invalidate()
        }
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (shelves.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
        lastFrame = now
        if (!dragging) scroll += (index - scroll) * min(1f, dt * 0.014f)
        val moving = abs(index - scroll) > 0.002f
        if (!moving) scroll = index.toFloat()

        val tile = tile()
        val cy = height * 0.46f
        val visible = (width / 2f / step() + 2).toInt()
        val range = (scroll.toInt() - visible).coerceAtLeast(0)..(scroll.toInt() + visible).coerceAtMost(shelves.size - 1)
        for (i in range.sortedByDescending { abs(it - scroll) }) {
            val d = i - scroll
            val near = 1f - min(1f, abs(d))
            val size = tile * (0.8f + 0.2f * near)
            val x = width / 2f + d * step()
            val r = RectF(x - size / 2, cy - size / 2, x + size / 2, cy + size / 2)
            drawTile(canvas, shelves[i], r, near)
            if (i == index) selectedRect = r
        }
        if (moving || dragging) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawTile(canvas: Canvas, shelf: Library.Shelf, r: RectF, near: Float) {
        val color = tileColor(shelf.id)
        val radius = r.width() * 0.16f
        paint.alpha = 255
        if (near > 0.5f && hasFocus()) {
            // Glow ring around the selected tile.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r.width() * 0.05f
            paint.color = (color and 0xFFFFFF) or (0x66 shl 24)
            canvas.drawRoundRect(RectF(r).apply { inset(-r.width() * 0.035f, -r.width() * 0.035f) }, radius * 1.2f, radius * 1.2f, paint)
            paint.strokeWidth = r.width() * 0.015f
            paint.color = 0xEEFFFFFF.toInt()
            canvas.drawRoundRect(RectF(r).apply { inset(-r.width() * 0.02f, -r.width() * 0.02f) }, radius * 1.1f, radius * 1.1f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Render.darker(color, 0.95f), Render.darker(color, 0.45f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = LinearGradient(r.left, r.top, r.left, r.centerY(), 0x30FFFFFF, 0, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null

        paint.alpha = (150 + 105 * near).toInt()
        Logos.get(context, shelf.id, Logos.Kind.SCENE, (r.height() * 0.48f).toInt()) { invalidate() }?.let { art ->
            fit(canvas, art, RectF(r.left + r.width() * 0.1f, r.top + r.height() * 0.1f, r.right - r.width() * 0.1f, r.top + r.height() * 0.64f))
        }
        val logo = Logos.get(context, shelf.id, Logos.Kind.LOGO, (r.height() * 0.15f).toInt()) { invalidate() }
        val logoBox = RectF(r.left + r.width() * 0.1f, r.top + r.height() * 0.7f, r.right - r.width() * 0.1f, r.top + r.height() * 0.9f)
        if (logo != null) fit(canvas, logo, logoBox)
        else {
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = r.height() * 0.09f
            paint.isFakeBoldText = true
            val text = android.text.TextUtils.ellipsize(shelf.title, android.text.TextPaint(paint), logoBox.width(), android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, logoBox.centerX() - paint.measureText(text) / 2, logoBox.centerY() + paint.textSize * 0.35f, paint)
            paint.isFakeBoldText = false
        }
        paint.alpha = 255
    }

    private fun fit(canvas: Canvas, bitmap: android.graphics.Bitmap, box: RectF) {
        val scale = min(box.width() / bitmap.width, box.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), paint)
    }

    private fun tileColor(id: String) = when (id) {
        Library.APPS -> 0xFF2E8B57.toInt()
        Library.RECENT -> 0xFF6B4FB0.toInt()
        // The console's signature color: a colored banner (Switch red), else its accent.
        else -> Look.of(id).let { look ->
            val banner = look.banner?.takeIf { !Render.isLight(it) && android.graphics.Color.red(it) + android.graphics.Color.green(it) + android.graphics.Color.blue(it) > 120 }
            (banner ?: look.accent).let { if (Render.isLight(it)) Render.darker(it, 0.75f) else it }
        }
    }
}
