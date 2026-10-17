package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Which console gets its own menu instead of the shelf. */
internal object Menus {
    fun styleFor(system: String): ConsoleMenu.Style? {
        if (App.prefs.getBoolean("shelf.$system", false)) return null
        return when (system) {
            "wii", "wiiu" -> ConsoleMenu.Style.WII
            "psp", "ps3" -> ConsoleMenu.Style.XMB
            "switch" -> ConsoleMenu.Style.SWITCH
            "n3ds", "nds" -> ConsoleMenu.Style.DS
            else -> null
        }
    }

    fun useShelf(system: String, shelf: Boolean) {
        App.prefs.edit().putBoolean("shelf.$system", shelf).apply()
        App.changed()
    }
}

/**
 * A console's own game menu, drawn in its spirit: the Wii channel grid, the PSP/PS3 XMB, the
 * Switch HOME row, and the 3DS HOME menu whose icon row sits on the bottom screen ([Part]).
 */
internal class ConsoleMenu(context: Context) : View(context) {
    enum class Style { WII, XMB, SWITCH, DS }

    /** For the 3DS menu split over two screens: the banner half, the icon half, or both. */
    enum class Part { BOTH, TOP, BOTTOM }

    var onSelect: (Library.Game) -> Unit = {}
    var onActivate: (Library.Game) -> Unit = {}
    var onMenu: (Library.Game) -> Unit = {}

    var style = Style.WII
        set(value) {
            if (value != field) invalidate()
            field = value
        }
    var part = Part.BOTH
        set(value) {
            if (value != field) invalidate()
            field = value
        }

    var games: List<Library.Game> = emptyList()
        set(value) {
            if (value === field) return
            field = value
            index = 0
            pos = 0f
            value.firstOrNull()?.let(onSelect)
            invalidate()
        }

    val current: Library.Game? get() = games.getOrNull(index)

    private var index = 0
    private var pos = 0f
    private var lastFrame = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var selectedRect = RectF()
    private val hits = ArrayList<Pair<Int, RectF>>()
    private var drag = 0f

    init {
        isFocusable = true
    }

    /** Follow a selection made on the other screen, without echoing it back. */
    fun sync(game: Library.Game?) {
        val i = games.indexOf(game)
        if (i >= 0 && i != index) {
            index = i
            invalidate()
        }
    }

    fun select(i: Int) {
        val next = i.coerceIn(0, (games.size - 1).coerceAtLeast(0))
        if (next == index || games.isEmpty()) return
        playSoundEffect(if (next < index) SoundEffectConstants.NAVIGATION_LEFT else SoundEffectConstants.NAVIGATION_RIGHT)
        index = next
        onSelect(games[index])
        invalidate()
    }

    fun activate() {
        playSoundEffect(SoundEffectConstants.CLICK)
        current?.let(onActivate)
    }

    fun selectedRectInWindow(): RectF {
        val at = IntArray(2).also(::getLocationInWindow)
        return RectF(selectedRect).apply { offset(at[0].toFloat(), at[1].toFloat()) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (style == Style.XMB) 0 else -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (style == Style.XMB) 0 else 1
            KeyEvent.KEYCODE_DPAD_UP -> when (style) { Style.WII -> -4; Style.XMB -> -1; else -> 0 }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (style) { Style.WII -> 4; Style.XMB -> 1; else -> 0 }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true.also { activate() }
            else -> return super.onKeyDown(keyCode, event)
        }
        val next = index + step
        if (step == 0 || next < 0 || next >= games.size) return false // let focus move on
        select(next)
        return true
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            drag = 0f
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            drag += if (style == Style.XMB) dy else dx
            val unit = (if (style == Style.XMB) height else width) * 0.12f
            while (abs(drag) > unit) {
                select(index + if (style == Style.WII) (if (drag > 0) 4 else -4) else (if (drag > 0) 1 else -1))
                drag -= if (drag > 0) unit else -unit
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = hits.lastOrNull { it.second.contains(e.x, e.y) }?.first ?: return false
            if (hit == index) activate() else select(hit)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            current?.let(onMenu)
        }
    })

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
        lastFrame = now
        val target = when (style) {
            Style.WII -> (index / 12).toFloat()
            Style.SWITCH -> (index - 2).coerceAtLeast(0).toFloat()
            else -> index.toFloat()
        }
        pos += (target - pos) * min(1f, dt * 0.014f)
        if (abs(target - pos) < 0.002f) pos = target
        hits.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        when (style) {
            Style.WII -> drawWii(canvas, w, h)
            Style.XMB -> drawXmb(canvas, w, h, now)
            Style.SWITCH -> drawSwitch(canvas, w, h, now)
            Style.DS -> when (part) {
                Part.TOP -> drawDsTop(canvas, RectF(0f, 0f, w, h))
                Part.BOTTOM -> drawDsBottom(canvas, RectF(0f, 0f, w, h))
                Part.BOTH -> {
                    drawDsTop(canvas, RectF(0f, 0f, w, h * 0.5f))
                    drawDsBottom(canvas, RectF(0f, h * 0.5f, w, h))
                }
            }
        }
        val animating = style == Style.XMB || style == Style.SWITCH // waves, pulsing border
        when {
            pos != target -> postInvalidateOnAnimation()
            animating -> postInvalidateDelayed(50)
            else -> lastFrame = 0L
        }
    }

    // ── Wii: 4 x 3 channels a page, the curved bar with the Wii button, clock and mail ──

    private fun drawWii(c: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(0f, 0f, 0f, h, 0xFFF4F4F4.toInt(), 0xFFD8D8D8.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.color = 0x0A000000
        var y = 0f
        while (y < h) { c.drawRect(0f, y, w, y + 1.5f, paint); y += h * 0.012f }

        val bar = h * 0.2f
        val pad = w * 0.035f
        val gap = w * 0.018f
        val cellW = (w - 2 * pad - 3 * gap) / 4
        val cellH = (h - bar - 2 * pad - 2 * gap) / 3
        val page = index / 12
        for (p in maxOf(0, page - 1)..page + 1) for (slot in 0 until 12) {
            val i = p * 12 + slot
            val x = pad + (slot % 4) * (cellW + gap) + (p - pos) * w
            if (x > w || x + cellW < 0) continue
            val r = RectF(x, pad + (slot / 4) * (cellH + gap), x + cellW, pad + (slot / 4) * (cellH + gap) + cellH)
            channel(c, r, games.getOrNull(i), i == index)
            if (i < games.size) hits += i to r
        }
        // Name tag under the selected channel, like the Wii's hover bubble.
        current?.let { g ->
            val r = selectedRect
            text.textSize = cellH * 0.13f
            text.color = 0xFF505050.toInt()
            val label = TextUtils.ellipsize(g.label, text, cellW * 1.4f, TextUtils.TruncateAt.END).toString()
            val tw = text.measureText(label) + cellH * 0.3f
            val tag = RectF(r.centerX() - tw / 2, r.bottom - cellH * 0.08f, r.centerX() + tw / 2, r.bottom + cellH * 0.18f)
            paint.color = 0xF2FFFFFF.toInt()
            c.drawRoundRect(tag, tag.height() / 2, tag.height() / 2, paint)
            stroke(c, tag, tag.height() / 2, 0xFFB5B5B5.toInt(), 1.5f)
            c.drawText(label, tag.centerX() - text.measureText(label) / 2, tag.centerY() + text.textSize * 0.35f, text)
        }

        // The bar: a light panel with a blue edge that dips between the two round buttons.
        val top = h - bar
        val edge = Path().apply {
            moveTo(0f, top + bar * 0.15f)
            cubicTo(w * 0.15f, top + bar * 0.15f, w * 0.18f, top + bar * 0.45f, w * 0.3f, top + bar * 0.45f)
            lineTo(w * 0.7f, top + bar * 0.45f)
            cubicTo(w * 0.82f, top + bar * 0.45f, w * 0.85f, top + bar * 0.15f, w, top + bar * 0.15f)
        }
        paint.color = 0xFFEDEDED.toInt()
        c.drawPath(Path(edge).apply { lineTo(w, h); lineTo(0f, h); close() }, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = bar * 0.03f
        paint.color = 0xFF34BEED.toInt()
        c.drawPath(edge, paint)
        paint.style = Paint.Style.FILL
        roundButton(c, w * 0.08f, top + bar * 0.55f, bar * 0.33f, "Wii")
        roundButton(c, w * 0.92f, top + bar * 0.55f, bar * 0.33f, null)
        envelope(c, w * 0.92f, top + bar * 0.55f, bar * 0.16f)
        text.color = 0xFF9A9A9A.toInt()
        text.textSize = bar * 0.3f
        val clock = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
        c.drawText(clock, w / 2 - text.measureText(clock) / 2, top + bar * 0.62f, text)
        text.textSize = bar * 0.16f
        val date = SimpleDateFormat("EEE M/d", Locale.getDefault()).format(Date())
        c.drawText(date, w / 2 - text.measureText(date) / 2, top + bar * 0.86f, text)
    }

    private fun channel(c: Canvas, r: RectF, game: Library.Game?, selected: Boolean) {
        val radius = r.height() * 0.16f
        paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
        stroke(c, r, radius, 0xFFB8B8B8.toInt(), 1.5f)
        if (game != null) {
            val art = Faces.art(context, game, (r.width()).toInt(), ::invalidate)
            val inner = RectF(r).apply { inset(r.width() * 0.04f, r.height() * 0.06f) }
            if (art != null) crop(c, art, inner, radius * 0.7f)
            else label(c, game.label, inner, 0xFF707070.toInt())
        } else {
            text.color = 0xFFD2D2D2.toInt()
            text.textSize = r.height() * 0.22f
            text.isFakeBoldText = true
            c.drawText("Wii", r.centerX() - text.measureText("Wii") / 2, r.centerY() + text.textSize * 0.35f, text)
            text.isFakeBoldText = false
        }
        if (selected) {
            selectedRect = r
            stroke(c, RectF(r).apply { inset(-4f, -4f) }, radius, 0x5534BEED, 10f)
            stroke(c, r, radius, 0xFF34BEED.toInt(), 4f)
        }
    }

    private fun roundButton(c: Canvas, x: Float, y: Float, r: Float, label: String?) {
        paint.shader = LinearGradient(0f, y - r, 0f, y + r, 0xFFFFFFFF.toInt(), 0xFFE2E2E2.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.08f
        paint.color = 0xFF34BEED.toInt()
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.FILL
        if (label != null) {
            text.color = 0xFF9A9A9A.toInt()
            text.textSize = r * 0.55f
            text.isFakeBoldText = true
            c.drawText(label, x - text.measureText(label) / 2, y + text.textSize * 0.35f, text)
            text.isFakeBoldText = false
        }
    }

    private fun envelope(c: Canvas, x: Float, y: Float, s: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.14f
        paint.color = 0xFF9A9A9A.toInt()
        c.drawRect(x - s, y - s * 0.65f, x + s, y + s * 0.65f, paint)
        c.drawPath(Path().apply { moveTo(x - s, y - s * 0.65f); lineTo(x, y + s * 0.1f); lineTo(x + s, y - s * 0.65f) }, paint)
        paint.style = Paint.Style.FILL
    }

    // ── XMB: month-colored gradient, drifting waves, category icons, a vertical game list ──

    private fun drawXmb(c: Canvas, w: Float, h: Float, now: Long) {
        val hue = Calendar.getInstance().get(Calendar.MONTH) * 30f
        val base = Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.55f))
        paint.shader = LinearGradient(0f, 0f, w, h, base, Render.darker(base, 0.35f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val t = now / 1000f
        for (k in 0 until 3) {
            val wave = Path()
            val amp = h * (0.05f + 0.02f * k)
            val mid = h * (0.58f + 0.04f * k)
            wave.moveTo(0f, mid)
            var x = 0f
            while (x <= w) {
                wave.lineTo(x, mid + amp * sin(x / w * 6.28f * (1 + 0.3f * k) + t * (0.35f + 0.1f * k) + k).toFloat())
                x += w / 60
            }
            paint.color = 0x16FFFFFF
            c.drawPath(Path(wave).apply { lineTo(w, h); lineTo(0f, h); close() }, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = 0x40FFFFFF
            c.drawPath(wave, paint)
            paint.style = Paint.Style.FILL
        }

        // Category row: settings, game (selected), video.
        val colX = w * 0.28f
        val catY = h * 0.2f
        val icon = h * 0.075f
        Glyph(Glyph.Kind.GEAR, 0x99FFFFFF.toInt()).apply { setBounds((colX - icon * 3.6f).toInt(), (catY - icon).toInt(), (colX - icon * 1.6f).toInt(), (catY + icon).toInt()) }.draw(c)
        controller(c, colX, catY, icon * 1.2f)
        Glyph(Glyph.Kind.DISC, 0x99FFFFFF.toInt()).apply { setBounds((colX + icon * 1.6f).toInt(), (catY - icon).toInt(), (colX + icon * 3.6f).toInt(), (catY + icon).toInt()) }.draw(c)
        text.color = Color.WHITE
        text.textSize = h * 0.035f
        c.drawText("Game", colX - text.measureText("Game") / 2, catY + icon * 1.9f, text)

        val iconH = h * 0.15f
        val step = iconH * 1.15f
        val centerY = h * 0.5f
        for (i in games.indices) {
            val d = i - pos
            if (abs(d) > 6) continue
            val sel = i == index
            val size = if (sel) 1f else 0.62f
            val ih = iconH * size
            val iw = ih * 1.8f
            val y = centerY + d * step + (if (d < 0) -step * 0.6f else if (d > 0) step * 0.4f else 0f)
            if (y < catY + icon * 2.6f && !sel) continue // items scrolled up hide behind the category row
            val r = RectF(colX - iw / 2, y - ih / 2, colX + iw / 2, y + ih / 2)
            paint.alpha = if (sel) 255 else 170
            val art = Faces.art(context, games[i], (iw).toInt(), ::invalidate)
            if (art != null) crop(c, art, r, ih * 0.06f) else {
                paint.color = 0x33FFFFFF
                c.drawRoundRect(r, ih * 0.06f, ih * 0.06f, paint)
            }
            paint.alpha = 255
            text.color = if (sel) Color.WHITE else 0xCCFFFFFF.toInt()
            text.textSize = h * if (sel) 0.045f else 0.034f
            text.isFakeBoldText = sel
            val name = TextUtils.ellipsize(games[i].label, text, w - r.right - w * 0.06f, TextUtils.TruncateAt.END).toString()
            c.drawText(name, r.right + w * 0.025f, y + text.textSize * (if (sel) -0.1f else 0.35f), text)
            text.isFakeBoldText = false
            if (sel) {
                selectedRect = r
                text.textSize = h * 0.03f
                text.color = 0xBBFFFFFF.toInt()
                val sub = Meta.get(context, games[i], ::invalidate)?.year ?: Library.title(games[i].system)
                c.drawText(sub, r.right + w * 0.025f, y + h * 0.045f, text)
            }
            hits += i to r
        }
    }

    private fun controller(c: Canvas, x: Float, y: Float, s: Float) {
        paint.color = Color.WHITE
        c.drawRoundRect(x - s, y - s * 0.45f, x + s, y + s * 0.45f, s * 0.4f, s * 0.4f, paint)
        paint.color = Render.darker(Color.WHITE, 0.4f)
        c.drawRect(x - s * 0.62f, y - s * 0.06f, x - s * 0.3f, y + s * 0.06f, paint)
        c.drawRect(x - s * 0.52f, y - s * 0.16f, x - s * 0.4f, y + s * 0.16f, paint)
        c.drawCircle(x + s * 0.45f, y - s * 0.1f, s * 0.08f, paint)
        c.drawCircle(x + s * 0.6f, y + s * 0.06f, s * 0.08f, paint)
    }

    // ── Switch HOME: a row of square tiles, the title above the pulsing one, round icons below ──

    private fun drawSwitch(c: Canvas, w: Float, h: Float, now: Long) {
        val dark = !Theme.current.light
        paint.color = if (dark) 0xFF2D2D2D.toInt() else 0xFFEBEBEB.toInt()
        c.drawRect(0f, 0f, w, h, paint)
        val tile = h * 0.46f
        val gap = tile * 0.05f
        val top = h * 0.24f
        val cyan = 0xFF00C3E3.toInt()
        for (i in games.indices) {
            val x = w * 0.05f + (i - pos) * (tile + gap)
            if (x > w || x + tile < 0) continue
            val r = RectF(x, top, x + tile, top + tile)
            val art = Faces.art(context, games[i], tile.toInt(), ::invalidate)
            if (art != null) crop(c, art, r, tile * 0.01f) else {
                paint.color = if (dark) 0xFF444444.toInt() else 0xFFD6D6D6.toInt()
                c.drawRect(r, paint)
                label(c, games[i].label, r, if (dark) Color.WHITE else 0xFF333333.toInt())
            }
            if (i == index) {
                selectedRect = r
                val pulse = 0.65f + 0.35f * sin(now / 260.0).toFloat()
                stroke(c, RectF(r).apply { inset(-tile * 0.035f, -tile * 0.035f) }, tile * 0.02f, Color.argb((255 * pulse).toInt(), 0, 195, 227), tile * 0.02f)
                text.color = cyan
                text.textSize = h * 0.045f
                val name = TextUtils.ellipsize(games[i].label, text, tile * 2.4f, TextUtils.TruncateAt.END).toString()
                c.drawText(name, r.left, top - h * 0.035f, text)
            }
            hits += i to r
        }
        // Round shortcut icons, as on the Switch HOME bar.
        val kinds = listOf(Glyph.Kind.GRID to 0xFFE60012.toInt(), Glyph.Kind.PLAY to 0xFFF39800.toInt(), Glyph.Kind.DISC to 0xFF0AB9E6.toInt(),
            Glyph.Kind.HOME to 0xFF9A9A9A.toInt(), Glyph.Kind.GEAR to 0xFF9A9A9A.toInt())
        val r = h * 0.05f
        val y = h * 0.86f
        kinds.forEachIndexed { k, (kind, color) ->
            val x = w / 2 + (k - 2) * r * 3f
            paint.color = if (dark) 0xFF3C3C3C.toInt() else Color.WHITE
            c.drawCircle(x, y, r, paint)
            Glyph(kind, color).apply { setBounds((x - r * 0.8f).toInt(), (y - r * 0.8f).toInt(), (x + r * 0.8f).toInt(), (y + r * 0.8f).toInt()) }.draw(c)
        }
    }

    // ── 3DS HOME: banner up top, icon row with the name bubble below ──

    private fun drawDsTop(c: Canvas, area: RectF) {
        pattern(c, area, 0xFFE4E4E4.toInt(), 0xFFEEEEEE.toInt())
        val game = current ?: return
        val logo = Faces.gameLogo(context, game, (area.height() * 0.5f).toInt(), ::invalidate)
        val art = logo ?: Faces.art(context, game, (area.height() * 0.8f).toInt(), ::invalidate)
        val box = RectF(area.left + area.width() * 0.1f, area.top + area.height() * 0.12f, area.right - area.width() * 0.1f, area.bottom - area.height() * 0.12f)
        if (art != null) selectedRect = fit(c, art, box)
        else {
            label(c, game.label, box, 0xFF404040.toInt())
            selectedRect = box
        }
        hits += index to selectedRect
    }

    private fun drawDsBottom(c: Canvas, area: RectF) {
        paint.color = 0xFFD9D9D9.toInt()
        c.drawRect(area, paint)
        val ah = area.height()
        // Toolbar of small icons.
        for (k in 0 until 5) {
            val s = ah * 0.09f
            val x = area.left + area.width() * 0.08f + k * s * 1.6f
            paint.color = Color.WHITE
            c.drawRoundRect(x, area.top + ah * 0.05f, x + s, area.top + ah * 0.05f + s, s * 0.2f, s * 0.2f, paint)
        }
        val icon = ah * 0.28f
        val gap = icon * 0.16f
        val y = area.top + ah * 0.5f
        for (i in games.indices) {
            val d = i - pos
            val x = area.centerX() + d * (icon + gap)
            if (x + icon < area.left || x - icon > area.right) continue
            val s = if (i == index) icon * 1.06f else icon
            val r = RectF(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
            paint.color = Color.WHITE
            c.drawRoundRect(r, s * 0.14f, s * 0.14f, paint)
            val inner = RectF(r).apply { inset(s * 0.07f, s * 0.07f) }
            val art = Faces.art(context, games[i], s.toInt(), ::invalidate)
            if (art != null) crop(c, art, inner, s * 0.08f) else label(c, games[i].label, inner, 0xFF505050.toInt())
            if (i == index) {
                stroke(c, RectF(r).apply { inset(-3f, -3f) }, s * 0.16f, 0xFF7CC242.toInt(), 5f)
                if (part == Part.BOTTOM) selectedRect = r
                // Name bubble with a tail pointing at the icon.
                text.textSize = ah * 0.07f
                text.color = 0xFF303030.toInt()
                val name = TextUtils.ellipsize(games[i].label, text, area.width() * 0.6f, TextUtils.TruncateAt.END).toString()
                val bw = text.measureText(name) + ah * 0.12f
                val bubble = RectF(area.centerX() - bw / 2, area.top + ah * 0.17f, area.centerX() + bw / 2, area.top + ah * 0.3f)
                paint.color = Color.WHITE
                c.drawRoundRect(bubble, ah * 0.03f, ah * 0.03f, paint)
                c.drawPath(Path().apply { moveTo(r.centerX() - ah * 0.025f, bubble.bottom); lineTo(r.centerX(), bubble.bottom + ah * 0.04f); lineTo(r.centerX() + ah * 0.025f, bubble.bottom); close() }, paint)
                c.drawText(name, bubble.centerX() - text.measureText(name) / 2, bubble.centerY() + text.textSize * 0.35f, text)
            }
            hits += i to r
        }
        // The "Open" bar.
        paint.color = 0xFFEFEFEF.toInt()
        c.drawRect(area.left, area.bottom - ah * 0.15f, area.right, area.bottom, paint)
        text.textSize = ah * 0.075f
        text.color = 0xFF404040.toInt()
        c.drawText("Open", area.centerX() - text.measureText("Open") / 2, area.bottom - ah * 0.05f, text)
        hits += index to RectF(area.left, area.bottom - ah * 0.15f, area.right, area.bottom)
    }

    private fun pattern(c: Canvas, area: RectF, bg: Int, tile: Int) {
        paint.color = bg
        c.drawRect(area, paint)
        paint.color = tile
        val s = area.height() * 0.12f
        var y = area.top + s * 0.2f
        while (y < area.bottom) {
            var x = area.left + s * 0.2f
            while (x < area.right) {
                c.drawRoundRect(x, y, x + s, y + s, s * 0.2f, s * 0.2f, paint)
                x += s * 1.25f
            }
            y += s * 1.25f
        }
    }

    // ── drawing helpers ──────────────────────────────────────────────────

    private fun crop(c: Canvas, b: Bitmap, dst: RectF, radius: Float) {
        val scale = maxOf(dst.width() / b.width, dst.height() / b.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val src = Rect(((b.width - sw) / 2).toInt(), ((b.height - sh) / 4).toInt(), ((b.width + sw) / 2).toInt(), ((b.height - sh) / 4 + sh).toInt())
        c.save()
        c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        c.drawBitmap(b, src, dst, paint)
        c.restore()
    }

    private fun fit(c: Canvas, b: Bitmap, box: RectF): RectF {
        val scale = min(box.width() / b.width, box.height() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        val r = RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
        c.drawBitmap(b, null, r, paint)
        return r
    }

    private fun label(c: Canvas, s: String, box: RectF, color: Int) {
        text.color = color
        text.textSize = box.height() * 0.14f
        val shown = TextUtils.ellipsize(s, text, box.width() * 0.9f, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, box.centerX() - text.measureText(shown) / 2, box.centerY() + text.textSize * 0.35f, text)
    }

    private fun stroke(c: Canvas, r: RectF, radius: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        c.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }
}
