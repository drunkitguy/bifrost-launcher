package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.LruCache
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Games standing spine-out on a shelf. The selected case slides partway out and turns to show its
 * cover. Left/right (or a swipe) moves along the shelf; A or a tap on the cover starts the game.
 */
internal class ShelfView(context: Context) : View(context) {
    var onSelect: (Library.Game) -> Unit = {}
    var onActivate: (Library.Game) -> Unit = {}
    var onMenu: (Library.Game) -> Unit = {}

    var games: List<Library.Game> = emptyList()
        set(value) {
            if (value === field) return
            field = value
            look = Look.of(value.firstOrNull()?.system.orEmpty())
            label = spineLabel(value.firstOrNull()?.system.orEmpty())
            index = 0
            scroll = 0f
            pull.clear()
            value.firstOrNull()?.let(onSelect)
            invalidate()
        }

    val current: Library.Game? get() = games.getOrNull(index)

    private var look = Look.of("")
    private var label = ""
    private var index = 0
    private var scroll = 0f
    /** How far each case is pulled out, 0 (on the shelf) to 1. Only cases not at rest are listed. */
    private val pull = HashMap<Int, Float>()
    private var lastFrame = 0L
    private var dragging = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val lights = arrayOfNulls<LightingColorFilter>(33)
    private var pulledBounds = RectF()

    // Geometry, in pixels, recomputed on size change.
    private var caseH = 1f
    private var caseW = 1f
    private var depth = 1f
    private var spacing = 1f
    private var cx = 0f
    private var cy = 0f
    private var focal = 1f

    init {
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        caseH = h * 0.66f
        cx = w / 2f
        cy = h * 0.06f + caseH / 2f
        focal = maxOf(w, h) * 1.3f
    }

    private fun geometry() {
        caseW = caseH * look.ratio
        depth = caseW * look.depth
        spacing = depth * 1.06f
    }

    fun select(i: Int) {
        val next = i.coerceIn(0, (games.size - 1).coerceAtLeast(0))
        if (next == index || games.isEmpty()) return
        pull[index] = pull[index] ?: 1f
        index = next
        pull[index] = pull[index] ?: 0f
        onSelect(games[index])
        invalidate()
    }

    fun activate() {
        current?.let(onActivate)
    }

    /** Where the pulled-out case's cover is on screen, in window coordinates. */
    fun coverRectInWindow(): RectF {
        val at = IntArray(2).also(::getLocationInWindow)
        return RectF(pulledBounds).apply { offset(at[0].toFloat(), at[1].toFloat()) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> (index > 0).also { if (it) select(index - 1) }
        KeyEvent.KEYCODE_DPAD_RIGHT -> (index < games.size - 1).also { if (it) select(index + 1) }
        KeyEvent.KEYCODE_BUTTON_L2 -> true.also { select(index - 10) }
        KeyEvent.KEYCODE_BUTTON_R2 -> true.also { select(index + 10) }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true.also { activate() }
        else -> super.onKeyDown(keyCode, event)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            dragging = true
            scroll = (scroll + dx / spacing).coerceIn(0f, (games.size - 1).toFloat().coerceAtLeast(0f))
            select(scroll.roundToInt())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            dragging = false
            select((scroll - vx / (spacing * 6)).roundToInt())
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (pulledBounds.contains(e.x, e.y)) activate()
            else select((scroll + (e.x - cx) / spacing).roundToInt())
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
        if (games.isEmpty()) return
        geometry()
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
        lastFrame = now
        val ease = min(1f, dt * 0.012f)
        var moving = false

        if (!dragging) {
            scroll += (index - scroll) * ease
            if (abs(index - scroll) < 0.002f) scroll = index.toFloat() else moving = true
        }
        for (i in pull.keys.toList()) {
            val target = if (i == index) 1f else 0f
            val p = pull[i]!! + (target - pull[i]!!) * ease
            if (abs(target - p) < 0.003f) {
                if (target == 0f) pull.remove(i) else pull[i] = 1f
            } else {
                pull[i] = p
                moving = true
            }
        }
        if (index !in pull) pull[index] = 0f.also { moving = true }

        drawShelf(canvas)

        val visible = (spacing.let { (width / 2f) / it + 3 }).toInt()
        val first = (scroll.toInt() - visible).coerceAtLeast(0)
        val last = (scroll.toInt() + visible).coerceAtMost(games.size - 1)
        // Far first: everything on the shelf, then the cases being pulled out.
        val order = (first..last).sortedBy { pull[it] ?: 0f }
        for (i in order) drawCase(canvas, i, pull[i] ?: 0f)

        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawShelf(canvas: Canvas) {
        val bottom = cy + caseH / 2f
        paint.shader = LinearGradient(0f, 0f, 0f, bottom, 0xFF0B0D11.toInt(), 0xFF1A1E26.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), bottom, paint)
        // The plank's top and its front edge.
        paint.shader = LinearGradient(0f, bottom, 0f, bottom + caseH * 0.05f, 0xFF3A2F26.toInt(), 0xFF2A221C.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, bottom, width.toFloat(), bottom + caseH * 0.05f, paint)
        paint.shader = LinearGradient(0f, bottom + caseH * 0.05f, 0f, bottom + caseH * 0.12f, 0xFF5A4636.toInt(), 0xFF2B221B.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, bottom + caseH * 0.05f, width.toFloat(), bottom + caseH * 0.12f, paint)
        paint.shader = null
    }

    private fun drawCase(canvas: Canvas, i: Int, p: Float) {
        val game = games[i]
        val theta = 90f - 66f * p
        val x = (i - scroll) * spacing
        val z = caseW / 2f - p * caseW * 0.85f
        val y = -p * caseH * 0.03f
        // Once it turns, the box takes the cover art's shape (N64 and SNES boxes are landscape).
        val cover = if (p > 0.02f) Faces.cover(context, game, look, (caseH * 1.25f).toInt()) { invalidate() } else null
        val w = cover?.let { caseH * it.width / it.height } ?: caseW
        val faces = Box.project(w, caseH, depth, x, y, z, theta, focal, cx, cy)

        if (p > 0.05f) {
            // Soft shadow on the plank under the pulled-out case.
            val sx = cx + x
            val sy = cy + caseH / 2f + caseH * 0.02f
            paint.shader = RadialGradient(sx, sy, caseW * 0.7f, (0x66 * p).toInt() shl 24, 0, Shader.TileMode.CLAMP)
            canvas.drawOval(sx - caseW * 0.7f, sy - caseH * 0.04f, sx + caseW * 0.7f, sy + caseH * 0.04f, paint)
            paint.shader = null
        }
        faces.spine?.let { quad ->
            draw(canvas, Faces.spine(context, game, look, label, depth.toInt(), caseH.toInt()), quad, light(sin(Math.toRadians(theta.toDouble())).toFloat()))
        }
        if (cover != null) faces.cover?.let { quad ->
            draw(canvas, cover, quad, light(cos(Math.toRadians(theta.toDouble())).toFloat()))
            if (i == index) pulledBounds = Box.bounds(quad)
        }
    }

    private fun draw(canvas: Canvas, bitmap: Bitmap, quad: FloatArray, filter: LightingColorFilter?) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        matrix.setPolyToPoly(floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh), 0, quad, 0, 4)
        paint.colorFilter = filter
        canvas.drawBitmap(bitmap, matrix, paint)
        paint.colorFilter = null
    }

    /** Faces turned away from the viewer get darker. */
    private fun light(facing: Float): LightingColorFilter? {
        val level = (0.55f + 0.45f * facing.coerceIn(0f, 1f))
        val step = (level * 32).toInt().coerceIn(0, 32)
        if (step >= 32) return null
        return lights[step] ?: run {
            val v = (step * 255 / 32)
            LightingColorFilter((v shl 16) or (v shl 8) or v, 0).also { lights[step] = it }
        }
    }

    private fun spineLabel(system: String): String = Look.of(system).bannerText
        ?: Library.title(system).removePrefix("Nintendo ").removePrefix("Sony ").removePrefix("Sega ")
            .removePrefix("Microsoft ").removePrefix("NEC ").removePrefix("SNK ").uppercase()
}

/** Perspective for one case. Pure math, so it is unit-tested. */
internal object Box {
    /** Screen quads (TL, TR, BR, BL as x,y pairs) of the faces turned toward the viewer, else null. */
    class Faces(val cover: FloatArray?, val spine: FloatArray?)

    /**
     * A case [w] x [h] x [d] centered at ([x], [y], [z]) (z grows away from the viewer, 0 is the
     * screen plane), turned [thetaDeg] about the vertical axis: 0 shows the cover, 90 the spine.
     */
    fun project(w: Float, h: Float, d: Float, x: Float, y: Float, z: Float, thetaDeg: Float, focal: Float, cx: Float, cy: Float): Faces {
        val t = Math.toRadians(thetaDeg.toDouble())
        val cos = cos(t).toFloat()
        val sin = sin(t).toFloat()
        fun face(vararg corners: Float): FloatArray {
            val out = FloatArray(8)
            for (k in 0 until 4) {
                val lx = corners[k * 3]
                val ly = corners[k * 3 + 1]
                val lz = corners[k * 3 + 2]
                val rx = lx * cos - lz * sin
                val rz = lx * sin + lz * cos
                val s = focal / (focal + z + rz)
                out[k * 2] = cx + (x + rx) * s
                out[k * 2 + 1] = cy + (y + ly) * s
            }
            return out
        }
        val hw = w / 2
        val hh = h / 2
        val hd = d / 2
        return Faces(
            cover = if (cos > 0.02f) face(-hw, -hh, -hd, hw, -hh, -hd, hw, hh, -hd, -hw, hh, -hd) else null,
            spine = if (sin > 0.02f) face(-hw, -hh, hd, -hw, -hh, -hd, -hw, hh, -hd, -hw, hh, hd) else null,
        )
    }

    fun bounds(quad: FloatArray) = RectF(
        minOf(quad[0], quad[2], quad[4], quad[6]), minOf(quad[1], quad[3], quad[5], quad[7]),
        maxOf(quad[0], quad[2], quad[4], quad[6]), maxOf(quad[1], quad[3], quad[5], quad[7]),
    )
}

/** Rendered spines and covers, cached; covers decode off the main thread. */
internal object Faces {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()

    fun spine(ctx: Context, game: Library.Game, look: Look, label: String, w: Int, h: Int): Bitmap {
        val key = "s:${game.docId}:$w:$h"
        return cache.get(key) ?: Render.spine(look, game.label, label, w, h).also { cache.put(key, it) }
    }

    /** The cover face. Until the art is decoded, a title-only cover. */
    fun cover(ctx: Context, game: Library.Game, look: Look, h: Int, onReady: () -> Unit): Bitmap {
        val key = "c:${game.docId}:$h:${Art.version}"
        cache.get(key)?.let { return it }
        val art = Art.game(ctx, game)
        val plainKey = "p:${game.docId}:$h"
        val plain = cache.get(plainKey) ?: Render.case(look, null, game.label, h, withSpine = false).also { cache.put(plainKey, it) }
        if (!art.exists()) return plain
        if (pending.add(key)) {
            Images.pool.execute {
                val cover = Images.decode(art, h)
                val face = Render.case(look, cover, game.label, h, withSpine = false)
                App.main.post {
                    pending.remove(key)
                    cache.put(key, face)
                    onReady()
                }
            }
        }
        return plain
    }

    /** The case front and the media for the insert animation, rendered now. */
    fun forInsert(ctx: Context, game: Library.Game, h: Int): Pair<Bitmap, Bitmap> {
        val look = Look.of(game.system)
        val cover = Art.game(ctx, game).takeIf { it.exists() }?.let { Images.decode(it, h) }
        return Render.case(look, cover, game.label, h) to Render.media(look, cover, game.label, (h * 0.62f).toInt())
    }
}
