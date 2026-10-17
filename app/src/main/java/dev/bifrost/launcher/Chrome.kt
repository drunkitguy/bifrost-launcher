package dev.bifrost.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small line icons: pills, headers, the dock. */
internal class Glyph(private val kind: Kind, private val color: Int = Ui.TEXT) : Drawable() {
    enum class Kind { HOME, GRID, DISC, GEAR, PLAY, USER, CLOCK, INFO, STOP, SORT, LIST, BACK }

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
            Kind.USER -> {
                canvas.drawCircle(cx, cy, s / 2, paint)
                canvas.drawCircle(cx, cy - s * 0.1f, s * 0.15f, paint)
                canvas.drawArc(cx - s * 0.27f, cy + s * 0.12f, cx + s * 0.27f, cy + s * 0.55f, 200f, 140f, false, paint)
            }
            Kind.CLOCK -> {
                canvas.drawCircle(cx, cy, s / 2, paint)
                canvas.drawLine(cx, cy, cx, cy - s * 0.28f, paint)
                canvas.drawLine(cx, cy, cx + s * 0.2f, cy + s * 0.1f, paint)
            }
            Kind.INFO -> {
                canvas.drawCircle(cx, cy, s / 2, paint)
                canvas.drawLine(cx, cy - s * 0.05f, cx, cy + s * 0.25f, paint)
                canvas.drawPoint(cx, cy - s * 0.24f, paint)
            }
            Kind.STOP -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(cx - s * 0.28f, cy - s * 0.28f, cx + s * 0.28f, cy + s * 0.28f, s * 0.08f, s * 0.08f, paint)
                paint.style = Paint.Style.STROKE
            }
            Kind.SORT -> for (k in 0..2) {
                val w = s * (0.5f - k * 0.14f)
                canvas.drawLine(cx - w, t + s * (0.2f + k * 0.3f), cx + w, t + s * (0.2f + k * 0.3f), paint)
            }
            Kind.LIST -> for (k in 0..2) {
                val y = t + s * (0.2f + k * 0.3f)
                canvas.drawPoint(l + s * 0.1f, y, paint)
                canvas.drawLine(l + s * 0.35f, y, l + s, y, paint)
            }
            Kind.BACK -> canvas.drawPath(Path().apply {
                moveTo(l + s, cy); lineTo(l, cy); moveTo(l + s * 0.4f, cy - s * 0.38f); lineTo(l, cy); lineTo(l + s * 0.4f, cy + s * 0.38f)
            }, paint)
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
