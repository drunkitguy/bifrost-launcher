package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

/**
 * Cocoon's top screen: full-bleed art for whatever is selected (a game's fan art or title screen,
 * or the console's scene on its color), status pills in the corners, play time and an info button.
 * With [tv] it is the backdrop of the single-screen layout and the game's logo moves top-left.
 */
internal class HeroView(context: Context) : View(context) {
    var tv = false
    var onStop: () -> Unit = {}

    /** The info card over the art (Y, or the i button). */
    var showInfo = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var stopRect = RectF()
    private var infoRect = RectF()

    private val game: Library.Game?
        get() = Library.highlight?.takeIf { Library.inside && it.system == Library.selected }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        when {
            showInfo -> showInfo = false
            stopRect.contains(event.x, event.y) -> onStop()
            infoRect.contains(event.x, event.y) -> showInfo = true
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(h * 0.075f, context.dp(46).toFloat()) // pill height
        val game = game
        if (game != null) drawGame(c, game, w, h, unit) else drawSystem(c, Library.selected, w, h)
        if (tv) {
            // Keep the pills and the row of games readable over bright art.
            paint.shader = LinearGradient(0f, h * 0.38f, 0f, h, 0, 0xE6000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.38f, w, h, paint)
            paint.shader = null
        }
        statusPills(c, w, unit)
        val pad = unit * 0.5f
        stopRect = RectF()
        infoRect = RectF()
        val playing = Library.nowPlaying()
        val buttons = listOfNotNull(Glyph.Kind.STOP.takeIf { playing != null }, Glyph.Kind.INFO.takeIf { game != null })
        if (buttons.isNotEmpty() && !tv) {
            val pw = unit * (0.5f + 1.1f * buttons.size)
            val r = RectF(w - pad - pw, h - pad - unit, w - pad, h - pad)
            pill(c, r)
            buttons.forEachIndexed { i, kind ->
                val cell = RectF(r.left + unit * (0.25f + 1.1f * i), r.top, r.left + unit * (1.35f + 1.1f * i), r.bottom)
                glyph(c, kind, cell, unit * 0.62f, if (kind == Glyph.Kind.INFO) 0xFF8EC5FF.toInt() else Color.WHITE)
                if (kind == Glyph.Kind.STOP) stopRect = cell else infoRect = cell
            }
        }
        if (game != null && showInfo) infoCard(c, game, w, h, unit)
        postInvalidateDelayed(30_000) // the clock
    }

    private fun drawGame(c: Canvas, game: Library.Game, w: Float, h: Float, unit: Float) {
        val hero = Faces.hero(context, game, width, ::invalidate)
        if (hero != null) cover(c, hero.bitmap, RectF(0f, 0f, w, h))
        else {
            fillSystem(c, game.system, w, h)
            val media = Faces.media(context, game, (h * 0.62f).toInt(), ::invalidate)
            fit(c, media, if (tv) RectF(w * 0.55f, h * 0.1f, w * 0.95f, h * 0.55f) else RectF(w * 0.2f, h * 0.15f, w * 0.8f, h * 0.85f))
        }
        val logo = if (hero == null || !hero.titled || tv) Faces.gameLogo(context, game, (h * 0.32f).toInt(), ::invalidate) else null
        if (tv) {
            if (logo != null) fit(c, logo, RectF(w * 0.04f, h * 0.13f, w * 0.42f, h * 0.36f), left = true)
            else {
                text.color = Color.WHITE
                text.textSize = h * 0.06f
                text.isFakeBoldText = true
                c.drawText(TextUtils.ellipsize(game.label, text, w * 0.45f, TextUtils.TruncateAt.END).toString(), w * 0.04f, h * 0.3f, text)
                text.isFakeBoldText = false
            }
            infoPills(c, game, w * 0.04f, h * 0.4f, unit)
        } else {
            if (logo != null) fit(c, logo, RectF(w * 0.2f, h * 0.22f, w * 0.8f, h * 0.68f))
            Library.playTime(game).takeIf { it > 0 }?.let { time ->
                val label = playTimeText(time)
                text.textSize = unit * 0.38f
                text.isFakeBoldText = true
                val pw = unit * 1.2f + text.measureText(label)
                val r = RectF(unit * 0.5f, h - unit * 1.5f, unit * 0.5f + pw, h - unit * 0.5f)
                paint.color = 0xFF2F7BEA.toInt()
                c.drawRoundRect(r, unit / 2, unit / 2, paint)
                glyph(c, Glyph.Kind.CLOCK, RectF(r.left, r.top, r.left + unit, r.bottom), unit * 0.55f, Color.WHITE)
                text.color = Color.WHITE
                c.drawText(label, r.left + unit * 0.95f, r.centerY() + text.textSize * 0.35f, text)
                text.isFakeBoldText = false
            }
        }
    }

    /** TV: play time, year and emulator under the logo. */
    private fun infoPills(c: Canvas, game: Library.Game, x0: Float, y: Float, unit: Float) {
        var x = x0
        val values = listOfNotNull(
            Library.playTime(game).takeIf { it > 0 }?.let(::playTimeText),
            Meta.get(context, game, ::invalidate)?.year,
            Library.title(game.system),
        )
        text.textSize = unit * 0.38f
        values.forEachIndexed { i, v ->
            val pw = text.measureText(v) + unit * 0.9f
            val r = RectF(x, y, x + pw, y + unit * 0.9f)
            paint.color = if (i == 0 && Library.playTime(game) > 0) 0xFF2F7BEA.toInt() else 0x66000000
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            text.color = Color.WHITE
            c.drawText(v, r.left + unit * 0.45f, r.centerY() + text.textSize * 0.35f, text)
            x += pw + unit * 0.25f
        }
    }

    private fun fillSystem(c: Canvas, system: String, w: Float, h: Float) {
        val color = Look.heroColor(system)
        paint.color = color
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w / 2, h * 0.55f, w * 0.45f, 0x33FFFFFF, 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawSystem(c: Canvas, system: String, w: Float, h: Float) {
        fillSystem(c, system, w, h)
        val box = if (tv) RectF(w * 0.2f, h * 0.12f, w * 0.8f, h * 0.56f) else RectF(w * 0.15f, h * 0.18f, w * 0.85f, h * 0.86f)
        val custom = Faces.systemArt(context, system, width, ::invalidate)
        val scene = custom ?: Logos.get(context, system, Logos.Kind.SCENE, box.height().toInt(), onReady = ::invalidate)
        when {
            scene != null -> fit(c, scene, box)
            system == Library.APPS -> glyph(c, Glyph.Kind.GRID, box, box.height() * 0.5f, Color.WHITE)
        }
        if (tv) Logos.get(context, system, Logos.Kind.LOGO, (h * 0.07f).toInt(), onReady = ::invalidate)?.let {
            fit(c, it, RectF(w * 0.3f, h * 0.58f, w * 0.7f, h * 0.66f))
        }
    }

    /** Cocoon's two status pills: L1 + profile on the left, R1 + Wi-Fi, battery and time on the right. */
    private fun statusPills(c: Canvas, w: Float, unit: Float) {
        val pad = unit * 0.5f
        val left = RectF(pad, pad, pad + unit * 2.5f, pad + unit)
        pill(c, left)
        chip(c, "L1", left.left + unit * 0.25f, left.centerY(), unit)
        glyph(c, Glyph.Kind.USER, RectF(left.left + unit * 1.15f, left.top, left.left + unit * 2.25f, left.bottom), unit * 0.72f, Color.WHITE)

        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val online = cm.getNetworkCapabilities(cm.activeNetwork) != null
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        text.textSize = unit * 0.42f
        text.isFakeBoldText = true
        val battery = "$level%"
        val width = unit * 3.4f + text.measureText(battery) + text.measureText(time)
        val right = RectF(w - pad - width, pad, w - pad, pad + unit)
        pill(c, right)
        var x = right.left + unit * 0.25f
        chip(c, "R1", x, right.centerY(), unit)
        x += unit * 1.0f
        wifi(c, x + unit * 0.35f, right.centerY() + unit * 0.2f, unit * 0.36f, online)
        x += unit * 0.85f
        val green = if (level <= 15) 0xFFFF5A5F.toInt() else 0xFF3BD47A.toInt()
        batteryIcon(c, RectF(x, right.centerY() - unit * 0.17f, x + unit * 0.55f, right.centerY() + unit * 0.17f), level, green, bm.isCharging)
        x += unit * 0.72f
        text.color = green
        c.drawText(battery, x, right.centerY() + text.textSize * 0.36f, text)
        x += text.measureText(battery) + unit * 0.35f
        text.color = Color.WHITE
        c.drawText(time, x, right.centerY() + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
    }

    private fun infoCard(c: Canvas, game: Library.Game, w: Float, h: Float, unit: Float) {
        paint.color = 0xD9000000.toInt()
        c.drawRect(0f, 0f, w, h, paint)
        val x = w * 0.06f
        var y = h * 0.16f
        val logo = Faces.gameLogo(context, game, (h * 0.2f).toInt(), ::invalidate)
        if (logo != null) {
            y = fit(c, logo, RectF(x, y, w * 0.5f, y + h * 0.2f), left = true).bottom + unit * 0.4f
        } else {
            text.color = Color.WHITE
            text.textSize = unit * 0.7f
            text.isFakeBoldText = true
            c.drawText(TextUtils.ellipsize(game.label, text, w * 0.85f, TextUtils.TruncateAt.END).toString(), x, y + text.textSize, text)
            text.isFakeBoldText = false
            y += text.textSize * 1.6f
        }
        val meta = Meta.get(context, game, ::invalidate)
        val emulator = Library.chosen(context, game)?.first?.label?.removeSuffix(" (Standalone)")
        val last = Library.lastPlayed(game)
        val facts = listOfNotNull(meta?.year, Library.title(game.system), emulator,
            if (last > 0) "Last played ${agoText(last, System.currentTimeMillis()).lowercase()}" else "Never played",
            Library.playTime(game).takeIf { it > 0 }?.let(::playTimeText)).joinToString("  ·  ")
        text.color = 0xFFB8C4D6.toInt()
        text.textSize = unit * 0.4f
        c.drawText(TextUtils.ellipsize(facts, text, w * 0.88f, TextUtils.TruncateAt.END).toString(), x, y + text.textSize, text)
        y += text.textSize * 2f
        meta?.description?.let { about ->
            text.color = Color.WHITE
            text.textSize = unit * 0.42f
            val lines = ((h - y - unit) / (text.textSize * 1.35f)).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(about, 0, about.length, text, (w * 0.88f).toInt())
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.15f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            c.save()
            c.translate(x, y)
            layout.draw(c)
            c.restore()
            text.color = 0x99FFFFFF.toInt()
            text.textSize = unit * 0.3f
            c.drawText("Wikipedia", x, h - unit * 0.6f, text)
        }
    }

    // ── drawing helpers ──────────────────────────────────────────────────

    private fun pill(c: Canvas, r: RectF) {
        paint.color = 0x47000000
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r.height() * 0.03f
        paint.color = 0x33FFFFFF
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
        paint.style = Paint.Style.FILL
    }

    private fun chip(c: Canvas, label: String, x: Float, cy: Float, unit: Float) {
        val r = RectF(x, cy - unit * 0.24f, x + unit * 0.62f, cy + unit * 0.24f)
        paint.color = 0x55FFFFFF
        c.drawRoundRect(r, unit * 0.1f, unit * 0.1f, paint)
        text.color = 0xFF2A2A2A.toInt()
        text.textSize = unit * 0.26f
        text.isFakeBoldText = true
        c.drawText(label, r.centerX() - text.measureText(label) / 2, cy + text.textSize * 0.36f, text)
        text.isFakeBoldText = false
    }

    private fun glyph(c: Canvas, kind: Glyph.Kind, cell: RectF, size: Float, color: Int) {
        Glyph(kind, color).apply {
            setBounds((cell.centerX() - size / 2).toInt(), (cell.centerY() - size / 2).toInt(), (cell.centerX() + size / 2).toInt(), (cell.centerY() + size / 2).toInt())
        }.draw(c)
    }

    private fun wifi(c: Canvas, cx: Float, cy: Float, r: Float, online: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.22f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (online) 0xFF8EC5FF.toInt() else 0x66FFFFFF
        for (k in listOf(1f, 0.66f, 0.33f)) c.drawArc(cx - r * k * 1.6f, cy - r * k * 1.6f, cx + r * k * 1.6f, cy + r * k * 1.6f, 225f, 90f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun batteryIcon(c: Canvas, body: RectF, level: Int, color: Int, charging: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = body.height() * 0.14f
        paint.color = color
        c.drawRoundRect(body, body.height() * 0.2f, body.height() * 0.2f, paint)
        paint.style = Paint.Style.FILL
        c.drawRect(body.right, body.centerY() - body.height() * 0.2f, body.right + body.height() * 0.15f, body.centerY() + body.height() * 0.2f, paint)
        val inset = body.height() * 0.22f
        c.drawRect(body.left + inset, body.top + inset, body.left + inset + (body.width() - 2 * inset) * level / 100f, body.bottom - inset, paint)
        if (charging) {
            paint.color = 0xFFFFD84A.toInt()
            c.drawCircle(body.centerX(), body.top - body.height() * 0.25f, body.height() * 0.12f, paint)
        }
    }

    private fun cover(c: Canvas, b: Bitmap, dst: RectF) {
        val scale = maxOf(dst.width() / b.width, dst.height() / b.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        c.drawBitmap(b, Rect(((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(), ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt()), dst, paint)
    }

    private fun fit(c: Canvas, b: Bitmap, box: RectF, left: Boolean = false): RectF {
        val scale = min(box.width() / b.width, box.height() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        val x = if (left) box.left else box.centerX() - w / 2
        val r = RectF(x, box.centerY() - h / 2, x + w, box.centerY() + h / 2)
        c.drawBitmap(b, null, r, paint)
        return r
    }
}
