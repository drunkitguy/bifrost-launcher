package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import com.caverock.androidsvg.SVG
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * System logos and console artwork from ES-DE's Slate theme (CC BY-NC-SA; the logos are their
 * owners' trademarks). Nothing ships in the APK: each device downloads what it shows, once.
 */
internal object Logos {
    private const val BASE = "https://gitlab.com/es-de/themes/slate-es-de/-/raw/master/"

    enum class Kind(val file: String) {
        /** The system's logo. */
        LOGO("logo.svg"),
        /** Console and its disc or cartridge side by side. */
        SCENE("consolegame.svg"),
        /** Just the console, cut out of [SCENE]. */
        CONSOLE("consolegame.svg"),
    }

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val missing = HashSet<String>()

    private fun folder(system: String) = if (system == Library.APPS) "androidapps" else system

    /**
     * [kind] art for [system], [h] px tall, or null until it has downloaded ([onReady] then fires).
     * [onDark] turns black lettering white so logos read on dark backgrounds.
     */
    fun get(ctx: Context, system: String, kind: Kind, h: Int, onDark: Boolean = true, onReady: () -> Unit = {}): Bitmap? {
        val key = "${folder(system)}/$kind/$h/$onDark"
        cache.get(key)?.let { return it }
        if (key in missing || !pending.add(key)) return null
        val app = ctx.applicationContext
        Images.pool.execute {
            val bitmap = runCatching { render(svgFile(app, folder(system), kind), kind, h, onDark) }.getOrNull()
            App.main.post {
                pending.remove(key)
                if (bitmap == null) missing += key
                else {
                    cache.put(key, bitmap)
                    onReady()
                }
            }
        }
        return null
    }

    private fun svgFile(ctx: Context, folder: String, kind: Kind): File {
        val file = File(ctx.filesDir, "logos/$folder-${kind.file}")
        if (!file.exists()) {
            val c = URL("$BASE$folder/images/${kind.file}").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            try {
                check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".part")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            } finally {
                c.disconnect()
            }
        }
        return file
    }

    private fun render(file: File, kind: Kind, h: Int, onDark: Boolean): Bitmap {
        val svg = file.inputStream().use { SVG.getFromInputStream(it) }
        val ratio = svg.documentAspectRatio.takeIf { it > 0 } ?: svg.documentViewBox?.let { it.width() / it.height() } ?: 1f
        val w = (h * ratio).toInt().coerceAtLeast(1)
        var bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, w.toFloat(), h.toFloat()))
        if (kind == Kind.CONSOLE) {
            val opaque = BooleanArray(w) { x -> (0 until h step 2).any { y -> bitmap.getPixel(x, y) ushr 24 > 16 } }
            consoleEnd(opaque)?.let { end -> bitmap = Bitmap.createBitmap(bitmap, 0, 0, end, h) }
        }
        if (onDark && kind == Kind.LOGO) {
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) px[i] = lightenDark(px[i])
            bitmap.setPixels(px, 0, w, 0, 0, w, h)
        }
        return bitmap
    }
}

/** Near-black pixels become white (same alpha): black lettering on a logo, readable on dark. */
internal fun lightenDark(argb: Int): Int {
    val a = argb ushr 24
    if (a == 0) return argb
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return if (r * 3 + g * 6 + b < 70 * 10) (a shl 24) or 0xF2F2F2 else argb
}

/**
 * Where the console ends in a console-and-media picture: the start of the widest empty column
 * band in the right part of the image (the gap before the disc or cartridge). Null if none.
 */
internal fun consoleEnd(opaque: BooleanArray): Int? {
    val n = opaque.size
    var best: IntRange? = null
    var start = -1
    for (x in (n * 0.35f).toInt() until n) {
        if (!opaque[x]) {
            if (start < 0) start = x
        } else if (start >= 0) {
            if (best == null || x - start > best.last - best.first) best = start until x
            start = -1
        }
    }
    return best?.takeIf { it.last - it.first + 1 >= n * 0.015f }?.first
}
