package dev.bifrost.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.view.View
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Console-style status: Wi-Fi, battery and time as icons in the top corner. */
internal class StatusBarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 15_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(context.dp(150), context.dp(24))

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val online = cm.getNetworkCapabilities(cm.activeNetwork) != null
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

        paint.textSize = h * 0.66f
        paint.color = Ui.TEXT
        var x = width - paint.measureText(time)
        canvas.drawText(time, x, h * 0.74f, paint)

        // Battery: outline, fill by level, nub; a bolt while charging.
        x -= h * 2.3f
        val body = RectF(x, h * 0.28f, x + h * 1.25f, h * 0.76f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.07f
        canvas.drawRoundRect(body, h * 0.08f, h * 0.08f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(body.right, h * 0.42f, body.right + h * 0.09f, h * 0.62f, paint)
        paint.color = if (level <= 15) 0xFFE5484D.toInt() else Ui.TEXT
        val inset = h * 0.1f
        canvas.drawRect(body.left + inset, body.top + inset, body.left + inset + (body.width() - 2 * inset) * level / 100f, body.bottom - inset, paint)
        if (bm.isCharging) {
            paint.color = 0xFFFFD84A.toInt()
            canvas.drawPath(Path().apply {
                moveTo(body.centerX() + h * 0.08f, body.top - h * 0.06f); lineTo(body.centerX() - h * 0.2f, body.centerY() + h * 0.04f)
                lineTo(body.centerX() + h * 0.01f, body.centerY() + h * 0.04f); lineTo(body.centerX() - h * 0.08f, body.bottom + h * 0.06f)
                lineTo(body.centerX() + h * 0.2f, body.centerY() - h * 0.04f); lineTo(body.centerX() - h * 0.01f, body.centerY() - h * 0.04f)
                close()
            }, paint)
        }

        // Wi-Fi: three arcs and a dot, dimmed when offline.
        x -= h * 1.3f
        paint.color = if (online) Ui.TEXT else 0x55FFFFFF
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.09f
        paint.strokeCap = Paint.Cap.ROUND
        val cx = x + h * 0.5f
        val cy = h * 0.8f
        for (r in listOf(0.62f, 0.42f, 0.22f)) canvas.drawArc(cx - h * r, cy - h * r, cx + h * r, cy + h * r, 225f, 90f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy - h * 0.02f, h * 0.06f, paint)
    }
}

/** The row of controller hints along the bottom: (A) Play  (B) Back ... */
internal class HintBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hints: List<Pair<String, String>> = emptyList()

    fun set(value: List<Pair<String, String>>) {
        if (value == hints) return
        hints = value
        contentDescription = value.joinToString { "${it.first}: ${it.second}" }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(30))

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h * 0.36f
        paint.textSize = h * 0.42f
        val gap = h * 0.9f
        val widths = hints.map { (button, label) -> chip(button, r) + h * 0.25f + paint.measureText(label) }
        var x = (width - widths.sum() - gap * (hints.size - 1)) / 2
        hints.forEachIndexed { i, (button, label) ->
            val w = chip(button, r)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * 0.06f
            paint.color = Ui.DIM
            canvas.drawRoundRect(x, h / 2 - r, x + w, h / 2 + r, r, r, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = h * if (button.length > 1) 0.3f else 0.42f
            paint.isFakeBoldText = true
            canvas.drawText(button, x + (w - paint.measureText(button)) / 2, h / 2 + paint.textSize * 0.36f, paint)
            paint.isFakeBoldText = false
            paint.textSize = h * 0.42f
            canvas.drawText(label, x + w + h * 0.25f, h / 2 + paint.textSize * 0.36f, paint)
            x += widths[i] + gap
        }
    }

    private fun chip(button: String, r: Float): Float {
        paint.textSize = height * 0.3f
        return if (button.length > 1) maxOf(2 * r, paint.measureText(button) + r) else 2 * r
    }
}

/** Small line icons for the dock. */
internal class Glyph(private val kind: Kind, private val color: Int = Ui.TEXT) : Drawable() {
    enum class Kind { HOME, GRID, DISC, GEAR, PLAY }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) * 0.7f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        paint.color = color
        paint.strokeWidth = s * 0.09f
        val l = cx - s / 2
        val t = cy - s / 2
        when (kind) {
            Kind.HOME -> canvas.drawPath(Path().apply {
                moveTo(l, t + s * 0.45f); lineTo(cx, t); lineTo(l + s, t + s * 0.45f)
                moveTo(l + s * 0.15f, t + s * 0.35f); lineTo(l + s * 0.15f, t + s); lineTo(l + s * 0.85f, t + s); lineTo(l + s * 0.85f, t + s * 0.35f)
            }, paint)
            Kind.GRID -> for (i in 0..1) for (j in 0..1) {
                val x = l + i * s * 0.56f
                val y = t + j * s * 0.56f
                canvas.drawRoundRect(x, y, x + s * 0.44f, y + s * 0.44f, s * 0.08f, s * 0.08f, paint)
            }
            Kind.DISC -> {
                canvas.drawCircle(cx, cy, s / 2, paint)
                canvas.drawCircle(cx, cy, s * 0.12f, paint)
            }
            Kind.GEAR -> {
                canvas.drawCircle(cx, cy, s * 0.3f, paint)
                canvas.drawCircle(cx, cy, s * 0.1f, paint)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val c = Math.cos(a).toFloat()
                    val n = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * s * 0.32f, cy + n * s * 0.32f, cx + c * s * 0.5f, cy + n * s * 0.5f, paint)
                }
            }
            Kind.PLAY -> {
                paint.style = Paint.Style.FILL
                canvas.drawPath(Path().apply { moveTo(l + s * 0.2f, t); lineTo(l + s, cy); lineTo(l + s * 0.2f, t + s); close() }, paint)
                paint.style = Paint.Style.STROKE
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A rounded tag like "12 games". */
internal fun Context.pill(text: String) = label(14f, Ui.TEXT).apply {
    this.text = text
    setPadding(dp(12), dp(5), dp(12), dp(5))
    background = rounded((Ui.TEXT and 0xFFFFFF) or 0x22000000, (Ui.TEXT and 0xFFFFFF) or 0x18000000)
}

internal fun TextView.showIf(value: String?) {
    text = value.orEmpty()
    visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
}
