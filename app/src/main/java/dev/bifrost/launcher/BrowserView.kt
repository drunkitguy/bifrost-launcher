package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Cocoon's bottom screen on black: the consoles as icons (console, logo, year), then a console's
 * games as their discs or cartridges, with a header pill (B, back, name), the sort pill and the
 * list toggle. The selection glows. With [row] it is the TV's single row along the bottom.
 */
internal class BrowserView(context: Context) : View(context) {
    var row = false
    var onOpen: (Any) -> Unit = {}
    var onMenu: (Any) -> Unit = {}
    var onSettings: () -> Unit = {}
    var onStop: () -> Unit = {}

    private var items: List<Any> = emptyList()
    private var index = 0
    private var scroll = 0f
    private var follow = true
    private var lastFrame = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val hits = ArrayList<Pair<Int, RectF>>()
    private val buttons = HashMap<String, RectF>()

    val current: Any? get() = items.getOrNull(index)

    init {
        isFocusable = true
    }

    private fun idOf(item: Any) = when (item) {
        is Library.Shelf -> item.id
        is Library.Game -> item.docId
        is Apps.Entry -> item.component.flattenToString()
        else -> ""
    }

    /** Reloads what to show, keeping the selection on the same thing. */
    fun bind() {
        val keep = if (!Library.inside) Library.selected else current?.takeIf { it !is Library.Shelf }?.let(::idOf) ?: Library.highlight?.docId
        items = when {
            !Library.inside -> Library.shelves()
            Library.selected == Library.APPS -> Apps.list
            else -> Library.gamesOf(Library.selected)
        }
        index = items.indexOfFirst { idOf(it) == keep }.takeIf { it >= 0 } ?: 0
        announce()
        invalidate()
    }

    private fun announce() {
        when (val item = current) {
            is Library.Shelf -> if (item.id != Library.selected) Library.selected = item.id
            is Library.Game -> Library.highlight = item
            else -> Library.highlight = null
        }
    }

    fun select(i: Int) {
        val next = i.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (next == index || items.isEmpty()) return
        playSoundEffect(if (next < index) SoundEffectConstants.NAVIGATION_LEFT else SoundEffectConstants.NAVIGATION_RIGHT)
        index = next
        follow = true
        announce()
        invalidate()
    }

    fun open() {
        playSoundEffect(SoundEffectConstants.CLICK)
        current?.let(onOpen)
    }

    private fun gamesShown() = Library.inside && Library.selected != Library.APPS

    private fun columns(): Int = when {
        row -> 1
        !Library.inside -> 2
        Library.selected == Library.APPS -> 4
        Library.listView -> 1
        Look.of(Library.selected).media.let { it == Look.Media.DISC || it == Look.Media.MINI_DISC || it == Look.Media.UMD } -> 3
        else -> 2
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val cols = columns()
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (row || cols > 1) -1 else 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (row || cols > 1) 1 else 0
            KeyEvent.KEYCODE_DPAD_UP -> if (row) 0 else -cols
            KeyEvent.KEYCODE_DPAD_DOWN -> if (row) 0 else cols
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true.also { open() }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (step != 0) select(index + step)
        return true
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            follow = false
            scroll = (scroll + if (row) dx else dy).coerceIn(0f, maxScroll)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            buttons.entries.firstOrNull { it.value.contains(e.x, e.y) }?.let { tap(it.key); return true }
            val hit = hits.lastOrNull { it.second.contains(e.x, e.y) }?.first ?: return false
            if (hit == index) open() else select(hit)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            current?.let(onMenu)
        }
    })

    private fun tap(button: String) {
        playSoundEffect(SoundEffectConstants.CLICK)
        when (button) {
            "back" -> Library.inside = false
            "sort" -> Library.sort = Library.Sort.entries[(Library.sort.ordinal + 1) % Library.Sort.entries.size]
            "list" -> Library.listView = !Library.listView
            "settings" -> onSettings()
            "stop" -> onStop()
        }
    }

    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    private var maxScroll = 0f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!row) c.drawColor(Color.BLACK)
        hits.clear()
        buttons.clear()
        val unit = min(h * if (row) 0.14f else 0.075f, context.dp(44).toFloat())
        val pad = unit * 0.45f
        val top = if (Library.inside || !row) header(c, w, unit, pad) else pad
        val bottomBar = if (!row && Library.nowPlaying() != null) nowPlaying(c, w, h, unit) else h

        val cols = columns()
        val list = gamesShown() && Library.listView && !row
        val cellW = if (row) (h - top) * 0.82f else (w - 2 * pad) / cols
        val cellH = when {
            row -> h - top - pad
            list -> unit * 1.7f
            !Library.inside -> cellW * 0.86f
            Library.selected == Library.APPS -> cellW * 1.15f
            else -> cellW * if (cols == 3) 1f else 1.1f
        }
        val rows = (items.size + cols - 1) / cols
        val span = if (row) items.size * cellW else rows * cellH
        val view = if (row) w - 2 * pad else bottomBar - top
        maxScroll = (span - view).coerceAtLeast(0f)
        if (follow) {
            val at = if (row) index * cellW else (index / cols) * cellH
            val size = if (row) cellW else cellH
            val target = when {
                row -> (at - view * 0.3f).coerceIn(0f, maxScroll)
                at < scroll -> at
                at + size > scroll + view -> at + size - view
                else -> scroll
            }
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
            lastFrame = now
            scroll += (target - scroll) * min(1f, dt * 0.016f)
            if (abs(target - scroll) < 0.5f) {
                scroll = target
                lastFrame = 0L
            } else postInvalidateOnAnimation()
        }

        c.save()
        c.clipRect(0f, top, w, bottomBar)
        items.forEachIndexed { i, item ->
            val x = if (row) pad + i * cellW - scroll else pad + (i % cols) * cellW
            val y = if (row) top else top + (i / cols) * cellH - scroll
            if (x > w || x + cellW < 0 || y > bottomBar || y + cellH < top) return@forEachIndexed
            val cell = RectF(x, y, x + cellW, y + cellH)
            hits += i to cell
            if (list) listRow(c, item, cell, unit, i == index) else tile(c, item, cell, i == index)
        }
        c.restore()
        if (items.isEmpty()) {
            text.color = 0xFF9AA0A6.toInt()
            text.textSize = unit * 0.42f
            val hint = if (Library.tree == null) "Choose your ROMs folder in settings" else if (Library.scanning) "Looking for games…" else "Nothing here yet"
            c.drawText(hint, w / 2 - text.measureText(hint) / 2, (top + bottomBar) / 2, text)
        }
    }

    /** "(B) <-  GameCube" on the left; sort pill and list toggle on the right (or settings at home). */
    private fun header(c: Canvas, w: Float, unit: Float, pad: Float): Float {
        val y = pad
        if (Library.inside) {
            text.textSize = unit * 0.5f
            text.isFakeBoldText = true
            val name = TextUtils.ellipsize(Library.title(Library.selected), text, w * 0.45f, TextUtils.TruncateAt.END).toString()
            val back = RectF(pad, y, pad + unit * 1.55f, y + unit)
            pill(c, back)
            chip(c, "B", back.left + unit * 0.2f, back.centerY(), unit)
            glyph(c, Glyph.Kind.BACK, RectF(back.left + unit * 0.7f, back.top, back.right, back.bottom), unit * 0.48f)
            buttons["back"] = RectF(back.left, back.top, back.right + text.measureText(name) + unit * 0.4f, back.bottom)
            text.color = Color.WHITE
            c.drawText(name, back.right + unit * 0.3f, back.centerY() + text.textSize * 0.36f, text)
            text.isFakeBoldText = false
            if (gamesShown()) {
                val toggle = RectF(w - pad - unit, y, w - pad, y + unit)
                circle(c, toggle)
                glyph(c, Glyph.Kind.LIST, toggle, unit * 0.5f)
                buttons["list"] = toggle
                text.textSize = unit * 0.42f
                text.isFakeBoldText = true
                val label = Library.sort.label
                val sw = text.measureText(label) + unit * 1.3f
                val sort = RectF(toggle.left - unit * 0.25f - sw, y, toggle.left - unit * 0.25f, y + unit)
                pill(c, sort)
                glyph(c, Glyph.Kind.SORT, RectF(sort.left, sort.top, sort.left + unit, sort.bottom), unit * 0.42f)
                text.color = Color.WHITE
                c.drawText(label, sort.left + unit * 0.9f, sort.centerY() + text.textSize * 0.36f, text)
                text.isFakeBoldText = false
                buttons["sort"] = sort
            }
            return y + unit + pad
        }
        val gear = RectF(w - pad - unit, y, w - pad, y + unit)
        circle(c, gear)
        glyph(c, Glyph.Kind.GEAR, gear, unit * 0.55f)
        buttons["settings"] = gear
        return y + unit * 0.4f
    }

    /** Bottom strip while a game runs: its name, the session time and Stop. */
    private fun nowPlaying(c: Canvas, w: Float, h: Float, unit: Float): Float {
        val game = Library.nowPlaying() ?: return h
        val r = RectF(unit * 0.45f, h - unit * 1.45f, w - unit * 0.45f, h - unit * 0.45f)
        pill(c, r)
        val minutes = ((System.currentTimeMillis() - Library.sessionStart()) / 60_000).coerceAtLeast(0)
        text.textSize = unit * 0.4f
        text.color = Color.WHITE
        val label = TextUtils.ellipsize("Playing ${game.label}  ·  ${minutes}m", text, r.width() - unit * 2.2f, TextUtils.TruncateAt.END).toString()
        c.drawText(label, r.left + unit * 0.5f, r.centerY() + text.textSize * 0.36f, text)
        val stop = RectF(r.right - unit * 1.2f, r.top, r.right - unit * 0.2f, r.bottom)
        glyph(c, Glyph.Kind.STOP, stop, unit * 0.55f)
        buttons["stop"] = stop
        postInvalidateDelayed(30_000)
        return r.top - unit * 0.2f
    }

    private fun tile(c: Canvas, item: Any, cell: RectF, selected: Boolean) {
        val inner = RectF(cell).apply { inset(cell.width() * 0.07f, cell.height() * 0.06f) }
        when (item) {
            is Library.Shelf -> {
                if (selected) glow(c, inner, round = false)
                val art = Logos.get(context, item.id, Logos.Kind.CONSOLE, (inner.height() * 0.5f).toInt(), onReady = ::invalidate)
                    ?: if (item.id == Library.APPS) null else Logos.get(context, item.id, Logos.Kind.SCENE, (inner.height() * 0.5f).toInt(), onReady = ::invalidate)
                val artBox = RectF(inner.left + inner.width() * 0.12f, inner.top + inner.height() * 0.08f, inner.right - inner.width() * 0.12f, inner.top + inner.height() * 0.6f)
                if (art != null) fit(c, art, artBox) else if (item.id == Library.APPS) glyph(c, Glyph.Kind.GRID, artBox, artBox.height() * 0.7f)
                val logo = Logos.get(context, item.id, Logos.Kind.LOGO, (inner.height() * 0.12f).toInt(), onReady = ::invalidate)
                val logoBox = RectF(inner.left + inner.width() * 0.15f, inner.top + inner.height() * 0.66f, inner.right - inner.width() * 0.15f, inner.top + inner.height() * 0.8f)
                if (logo != null) fit(c, logo, logoBox) else label(c, item.title, logoBox, inner.height() * 0.08f, Color.WHITE)
                Logos.year(context, item.id, ::invalidate)?.let {
                    label(c, it, RectF(inner.left, logoBox.bottom, inner.right, logoBox.bottom + inner.height() * 0.1f), inner.height() * 0.06f, 0xFFCFD3DA.toInt())
                }
            }
            is Library.Game -> {
                val media = Faces.media(context, item, inner.width().toInt(), ::invalidate)
                val shown = fit(c, media, inner)
                val round = Look.of(item.system).media.let { it == Look.Media.DISC || it == Look.Media.MINI_DISC }
                if (selected) glow(c, RectF(shown).apply { inset(-shown.width() * 0.035f, -shown.width() * 0.035f) }, round)
            }
            is Apps.Entry -> {
                if (selected) glow(c, inner, round = false)
                val icon = inner.width() * 0.56f
                item.icon.setBounds((inner.centerX() - icon / 2).toInt(), (inner.top + inner.height() * 0.12f).toInt(), (inner.centerX() + icon / 2).toInt(), (inner.top + inner.height() * 0.12f + icon).toInt())
                item.icon.draw(c)
                label(c, item.label, RectF(inner.left, inner.top + inner.height() * 0.12f + icon, inner.right, inner.bottom), inner.height() * 0.1f, Color.WHITE)
            }
        }
    }

    private fun listRow(c: Canvas, item: Any, cell: RectF, unit: Float, selected: Boolean) {
        val game = item as? Library.Game ?: return
        val r = RectF(cell).apply { inset(0f, unit * 0.12f) }
        if (selected) {
            paint.color = 0x22FFFFFF
            c.drawRoundRect(r, unit * 0.3f, unit * 0.3f, paint)
            glow(c, r, round = false)
        }
        val thumb = Faces.media(context, game, (r.height() * 2).toInt(), ::invalidate)
        fit(c, thumb, RectF(r.left + unit * 0.2f, r.top + unit * 0.1f, r.left + unit * 0.2f + r.height() - unit * 0.2f, r.bottom - unit * 0.1f))
        val x = r.left + r.height() + unit * 0.3f
        text.color = Color.WHITE
        text.textSize = unit * 0.45f
        text.isFakeBoldText = true
        c.drawText(TextUtils.ellipsize(game.label, text, r.right - x - unit * 0.3f, TextUtils.TruncateAt.END).toString(), x, r.centerY() - unit * 0.05f, text)
        text.isFakeBoldText = false
        val last = Library.lastPlayed(game)
        val sub = listOfNotNull(Library.playTime(game).takeIf { it > 0 }?.let(::playTimeText),
            if (last > 0) agoText(last, System.currentTimeMillis()) else null).joinToString("  ·  ").ifEmpty { "Not played yet" }
        text.color = 0xFF9AA0A6.toInt()
        text.textSize = unit * 0.34f
        c.drawText(sub, x, r.centerY() + unit * 0.42f, text)
    }

    // ── drawing helpers ──────────────────────────────────────────────────

    /** Cocoon's selection: a light-blue ring with a soft halo, round for discs. */
    private fun glow(c: Canvas, r: RectF, round: Boolean) {
        val color = Ui.ACCENT
        val radius = if (round) r.width() / 2 else min(r.width(), r.height()) * 0.16f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r.width() * 0.05f
        paint.color = (color and 0xFFFFFF) or 0x40000000
        if (round) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, paint) else c.drawRoundRect(r, radius, radius, paint)
        paint.strokeWidth = r.width() * 0.018f
        paint.color = color
        if (round) c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, paint) else c.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun pill(c: Canvas, r: RectF) {
        paint.color = 0xFF2A2C31.toInt()
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
    }

    private fun circle(c: Canvas, r: RectF) {
        paint.color = 0xFF2A2C31.toInt()
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, paint)
    }

    private fun chip(c: Canvas, label: String, x: Float, cy: Float, unit: Float) {
        paint.color = 0xFF4A4D55.toInt()
        c.drawCircle(x + unit * 0.24f, cy, unit * 0.24f, paint)
        text.color = 0xFFDADDE2.toInt()
        text.textSize = unit * 0.26f
        text.isFakeBoldText = true
        c.drawText(label, x + unit * 0.24f - text.measureText(label) / 2, cy + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
    }

    private fun glyph(c: Canvas, kind: Glyph.Kind, cell: RectF, size: Float) {
        Glyph(kind, Color.WHITE).apply {
            setBounds((cell.centerX() - size / 2).toInt(), (cell.centerY() - size / 2).toInt(), (cell.centerX() + size / 2).toInt(), (cell.centerY() + size / 2).toInt())
        }.draw(c)
    }

    private fun label(c: Canvas, s: String, box: RectF, size: Float, color: Int) {
        text.color = color
        text.textSize = size
        val shown = TextUtils.ellipsize(s, text, box.width(), TextUtils.TruncateAt.END).toString()
        c.drawText(shown, box.centerX() - text.measureText(shown) / 2, box.centerY() + size * 0.36f, text)
    }

    private fun fit(c: Canvas, b: Bitmap, box: RectF): RectF {
        val scale = min(box.width() / b.width, box.height() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        val r = RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
        c.drawBitmap(b, null, r, paint)
        return r
    }
}
