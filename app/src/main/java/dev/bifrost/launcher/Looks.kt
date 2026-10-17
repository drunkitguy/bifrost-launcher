package dev.bifrost.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min

/** How a system's games look on the shelf: the case, the media inside it, and the console. */
internal class Look(
    /** Case width / height when there is no box art to take the shape from. */
    val ratio: Float,
    val caseColor: Int,
    /** Plastic keep case (spine, clear sleeve, gloss) or cardboard box. */
    val plastic: Boolean,
    val banner: Int? = null,
    val bannerText: String? = null,
    val media: Media,
    val mediaColor: Int,
    val console: Console,
    val consoleColor: Int,
    val accent: Int = consoleColor,
    /** Case thickness / case width: how wide the spine is. */
    val depth: Float = if (plastic) 0.1f else 0.19f,
    /** Wii-style colored corner on the spine. */
    val cornerStripe: Boolean = false,
) {
    enum class Media { DISC, MINI_DISC, UMD, CART, CART_SMALL, CARD }
    enum class Console { CUBE, SLIM, TOP_SLOT, HANDHELD, TABLET }

    companion object {
        private const val BLACK = 0xFF16171A.toInt()
        private const val WHITE = 0xFFEDEEF0.toInt()
        private const val GREY = 0xFF9A9CA3.toInt()
        private const val CLEAR = 0xFFB9C3CF.toInt()

        private val dvdBlack = Look(0.71f, BLACK, true, media = Media.DISC, mediaColor = 0xFFC9CED6.toInt(), console = Console.SLIM, consoleColor = 0xFF24262B.toInt())
        private val jewel = Look(1.13f, CLEAR, true, depth = 0.075f, media = Media.DISC, mediaColor = 0xFF2B2C30.toInt(), console = Console.SLIM, consoleColor = 0xFFB8BBC2.toInt())
        private val box = Look(0.72f, 0xFFDAD4C4.toInt(), false, media = Media.CART, mediaColor = GREY, console = Console.TOP_SLOT, consoleColor = 0xFF8D8F96.toInt())

        private val looks = mapOf(
            "gc" to Look(0.71f, BLACK, true, media = Media.MINI_DISC, mediaColor = 0xFFC8CCD3.toInt(), console = Console.CUBE, consoleColor = 0xFF4B3F8F.toInt()),
            "wii" to Look(0.71f, WHITE, true, banner = WHITE, bannerText = "Wii", media = Media.DISC, mediaColor = 0xFFF2F3F5.toInt(), console = Console.SLIM, consoleColor = WHITE, accent = 0xFF35A7E8.toInt(), cornerStripe = true),
            "wiiu" to Look(0.71f, 0xFF1E8FD6.toInt(), true, banner = 0xFF1E8FD6.toInt(), bannerText = "Wii U", media = Media.DISC, mediaColor = 0xFFE9ECF0.toInt(), console = Console.SLIM, consoleColor = BLACK, accent = 0xFF1E8FD6.toInt()),
            "ps2" to Look(0.71f, BLACK, true, banner = BLACK, bannerText = "PS2", media = Media.DISC, mediaColor = 0xFF3A55C8.toInt(), console = Console.SLIM, consoleColor = 0xFF1C1D22.toInt(), accent = 0xFF3A55C8.toInt()),
            "ps3" to Look(0.79f, 0xFF2E3A48.toInt(), true, banner = BLACK, bannerText = "PS3", media = Media.DISC, mediaColor = 0xFFCFD3DA.toInt(), console = Console.SLIM, consoleColor = BLACK),
            "psx" to jewel,
            "dreamcast" to Look(1.13f, CLEAR, true, depth = 0.075f, media = Media.DISC, mediaColor = 0xFFD5D8DD.toInt(), console = Console.CUBE, consoleColor = WHITE, accent = 0xFFF26B1D.toInt()),
            "saturn" to Look(1.13f, CLEAR, true, depth = 0.075f, media = Media.DISC, mediaColor = 0xFF2B2C30.toInt(), console = Console.SLIM, consoleColor = 0xFF3A3B40.toInt()),
            "segacd" to jewel, "megacd" to jewel, "pcenginecd" to jewel, "neogeocd" to jewel,
            "psp" to Look(0.58f, CLEAR, true, media = Media.UMD, mediaColor = 0xFF1B1C20.toInt(), console = Console.HANDHELD, consoleColor = BLACK),
            "psvita" to Look(0.78f, 0xFF5B7FB8.toInt(), true, media = Media.CARD, mediaColor = 0xFF2A2B30.toInt(), console = Console.HANDHELD, consoleColor = BLACK),
            "n3ds" to Look(0.91f, WHITE, true, banner = WHITE, bannerText = "3DS", media = Media.CARD, mediaColor = 0xFF5A5C63.toInt(), console = Console.HANDHELD, consoleColor = 0xFF2C4C8F.toInt()),
            "nds" to Look(0.91f, WHITE, true, banner = WHITE, bannerText = "DS", media = Media.CARD, mediaColor = 0xFF5A5C63.toInt(), console = Console.HANDHELD, consoleColor = 0xFFC4C6CC.toInt()),
            "switch" to Look(0.62f, 0xFFD7101F.toInt(), true, banner = 0xFFD7101F.toInt(), bannerText = "SWITCH", media = Media.CARD, mediaColor = 0xFF3A3B40.toInt(), console = Console.TABLET, consoleColor = 0xFF2A2B30.toInt()),
            "n64" to box.with(media = Media.CART, mediaColor = 0xFF5B5D63.toInt(), consoleColor = 0xFF3A3B40.toInt()),
            "snes" to box.with(mediaColor = 0xFF9EA0A6.toInt(), consoleColor = 0xFFC9CACE.toInt(), accent = 0xFF6B4FB0.toInt()),
            "sfc" to box.with(mediaColor = 0xFF9EA0A6.toInt(), consoleColor = 0xFFC9CACE.toInt()),
            "nes" to box.with(mediaColor = 0xFF8D8F96.toInt(), consoleColor = 0xFFBDBEC2.toInt(), accent = 0xFFC8102E.toInt()),
            "famicom" to box,
            "gba" to box.with(ratio = 0.86f, media = Media.CART_SMALL, mediaColor = 0xFF55575E.toInt(), console = Console.HANDHELD, consoleColor = 0xFF4E3A9C.toInt()),
            "gbc" to box.with(ratio = 0.86f, media = Media.CART_SMALL, mediaColor = 0xFF2B2C30.toInt(), console = Console.HANDHELD, consoleColor = 0xFF7A3FA0.toInt()),
            "gb" to box.with(ratio = 0.86f, media = Media.CART_SMALL, mediaColor = GREY, console = Console.HANDHELD, consoleColor = 0xFFC9C8C0.toInt()),
            "genesis" to Look(0.69f, BLACK, true, media = Media.CART, mediaColor = 0xFF1E1F23.toInt(), console = Console.TOP_SLOT, consoleColor = 0xFF1C1D20.toInt(), accent = 0xFFD7101F.toInt()),
            "megadrive" to Look(0.69f, BLACK, true, media = Media.CART, mediaColor = 0xFF1E1F23.toInt(), console = Console.TOP_SLOT, consoleColor = 0xFF1C1D20.toInt()),
            "mastersystem" to box.with(mediaColor = BLACK, consoleColor = 0xFF1C1D20.toInt()),
            "xbox" to dvdBlack.with(caseColor = 0xFF2F8F3A.toInt(), consoleColor = 0xFF1C1D20.toInt(), accent = 0xFF6CC24A.toInt()),
            "xbox360" to dvdBlack.with(caseColor = 0xFF2F8F3A.toInt(), consoleColor = WHITE, accent = 0xFF6CC24A.toInt()),
        )

        fun of(system: String): Look = looks[system] ?: dvdBlack

        /** The flat color a console's top screen sits on, as in Cocoon. */
        fun heroColor(system: String): Int = heroColors[system] ?: of(system).let { look ->
            val banner = look.banner?.takeIf { !Render.isLight(it) && Color.red(it) + Color.green(it) + Color.blue(it) > 120 }
            (banner ?: look.accent).let { if (Render.isLight(it)) Render.darker(it, 0.8f) else it }
        }

        private val heroColors = mapOf(
            "gc" to 0xFF5A55E6.toInt(), "switch" to 0xFFE8202E.toInt(), "n3ds" to 0xFFB8CBE6.toInt(), "nds" to 0xFFC9CDD4.toInt(),
            "wii" to 0xFFD9EEF8.toInt(), "wiiu" to 0xFF1E8FD6.toInt(), "ps2" to 0xFF1D2B6E.toInt(), "psx" to 0xFF8B8F98.toInt(),
            "psp" to 0xFF2B2D33.toInt(), "ps3" to 0xFF161A24.toInt(), "n64" to 0xFF2E7D32.toInt(), "snes" to 0xFF6B4FB0.toInt(),
            "gba" to 0xFF4E3A9C.toInt(), "dreamcast" to 0xFFF26B1D.toInt(), "genesis" to 0xFF1C1D20.toInt(), "megadrive" to 0xFF1C1D20.toInt(),
            Library.APPS to 0xFF2E8B57.toInt(),
        )
    }

    fun with(
        ratio: Float = this.ratio,
        caseColor: Int = this.caseColor,
        media: Media = this.media,
        mediaColor: Int = this.mediaColor,
        console: Console = this.console,
        consoleColor: Int = this.consoleColor,
        accent: Int = this.accent,
    ) = Look(ratio, caseColor, plastic, banner, bannerText, media, mediaColor, console, consoleColor, accent, depth, cornerStripe)
}

/** Draws discs, cartridges and cards from the box art; nothing trademarked ships. */
internal object Render {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Cover art, center-cropped into [dst]; without art, the title on the system's colors. */
    private fun art(c: Canvas, cover: Bitmap?, title: String, look: Look, dst: RectF, radius: Float) {
        c.save()
        c.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
        if (cover != null) {
            val scale = maxOf(dst.width() / cover.width, dst.height() / cover.height)
            val sw = dst.width() / scale
            val sh = dst.height() / scale
            val src = Rect(((cover.width - sw) / 2).toInt(), ((cover.height - sh) / 2).toInt(),
                ((cover.width + sw) / 2).toInt(), ((cover.height + sh) / 2).toInt())
            c.drawBitmap(cover, src, dst, paint)
        } else {
            paint.shader = LinearGradient(dst.left, dst.top, dst.right, dst.bottom,
                darker(look.accent, 0.7f), darker(look.accent, 0.25f), Shader.TileMode.CLAMP)
            c.drawRect(dst, paint)
            paint.shader = null
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dst.width() * 0.11f
                isFakeBoldText = true
            }
            val layout = StaticLayout.Builder.obtain(title, 0, title.length, text, (dst.width() * 0.84f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(4).build()
            c.translate(dst.left + dst.width() * 0.08f, dst.centerY() - layout.height / 2f)
            layout.draw(c)
        }
        c.restore()
    }

    /** [logo] fitted and centered in [box]. */
    private fun logoIn(c: Canvas, logo: Bitmap?, box: RectF) {
        logo ?: return
        val scale = min(box.width() / logo.width, box.height() / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        c.drawBitmap(logo, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), paint)
    }

    /**
     * The disc, cartridge or card, like the real ones: box art as the label and the system's logo
     * on its band ([logo] should have dark lettering). [size] is its longest side.
     */
    fun media(look: Look, cover: Bitmap?, title: String, logo: Bitmap?, size: Int): Bitmap = when (look.media) {
        Look.Media.DISC, Look.Media.MINI_DISC -> disc(look, cover, title, logo, size)
        Look.Media.UMD -> umd(look, cover, title, size)
        Look.Media.CART -> cart(look, cover, title, logo, size, (size * 0.78f).toInt(), card = false)
        Look.Media.CART_SMALL -> cart(look, cover, title, logo, (size * 0.9f).toInt(), size, card = false)
        Look.Media.CARD -> cart(look, cover, title, logo, (size * 0.92f).toInt(), size, card = true)
    }

    private fun disc(look: Look, cover: Bitmap?, title: String, logo: Bitmap?, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cx = size / 2f
        val r = size / 2f
        paint.shader = RadialGradient(cx, cx, r, intArrayOf(0xFFF2F4F7.toInt(), 0xFFC9CED6.toInt(), 0xFF9AA0AA.toInt()),
            floatArrayOf(0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r, paint)
        paint.shader = null
        val ring = Path().apply {
            addCircle(cx, cx, r * 0.95f, Path.Direction.CW)
            addCircle(cx, cx, r * 0.3f, Path.Direction.CCW)
        }
        c.save()
        c.clipPath(ring)
        art(c, cover, title, look, RectF(0f, 0f, size.toFloat(), size.toFloat()), 0f)
        // The printed band across the bottom with the system's logo, as on GameCube, Wii and PS2 discs.
        val bandTop = cx + r * 0.42f
        paint.color = look.mediaColor
        c.drawRect(0f, bandTop, size.toFloat(), size.toFloat(), paint)
        logoIn(c, logo, RectF(cx - r * 0.55f, bandTop + r * 0.06f, cx + r * 0.55f, bandTop + r * 0.3f))
        c.restore()
        // Rim, rainbow sheen, clear hub.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.04f
        paint.color = 0xFFDDE1E6.toInt()
        c.drawCircle(cx, cx, r * 0.96f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = SweepGradient(cx, cx, intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF, 0x2400E5FF, 0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF), null)
        c.drawCircle(cx, cx, r, paint)
        paint.shader = null
        paint.color = 0xEEC5CAD1.toInt()
        c.drawCircle(cx, cx, r * 0.3f, paint)
        paint.color = 0xFF3A3D44.toInt()
        c.drawCircle(cx, cx, r * 0.17f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawCircle(cx, cx, r * 0.1f, paint)
        paint.xfermode = null
        return out
    }

    private fun umd(look: Look, cover: Bitmap?, title: String, size: Int): Bitmap {
        val w = (size * 0.9f).toInt()
        val out = Bitmap.createBitmap(w, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shell = Path().apply {
            moveTo(w * 0.1f, 0f); lineTo(w * 0.9f, 0f)
            quadTo(w.toFloat(), 0f, w.toFloat(), w * 0.1f); lineTo(w.toFloat(), size * 0.62f)
            quadTo(w.toFloat(), size.toFloat(), w / 2f, size.toFloat())
            quadTo(0f, size.toFloat(), 0f, size * 0.62f); lineTo(0f, w * 0.1f)
            quadTo(0f, 0f, w * 0.1f, 0f); close()
        }
        paint.color = look.mediaColor
        c.drawPath(shell, paint)
        c.save()
        c.clipPath(shell)
        art(c, cover, title, look, RectF(w * 0.08f, size * 0.05f, w * 0.92f, size * 0.6f), w * 0.03f)
        c.restore()
        paint.color = 0x55FFFFFF
        c.drawCircle(w / 2f, size * 0.78f, w * 0.14f, paint)
        return out
    }

    /** Cartridge or card: white label strip with the system logo on top, box art below. */
    private fun cart(look: Look, cover: Bitmap?, title: String, logo: Bitmap?, w: Int, h: Int, card: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = min(w, h) * 0.06f
        val body = Path().apply {
            if (card) { // 3DS/DS/Switch cards have a cut corner
                val cut = w * 0.1f
                moveTo(r, 0f); lineTo(w - cut, 0f); lineTo(w.toFloat(), cut); lineTo(w.toFloat(), h - r)
                quadTo(w.toFloat(), h.toFloat(), w - r, h.toFloat()); lineTo(r, h.toFloat())
                quadTo(0f, h.toFloat(), 0f, h - r); lineTo(0f, r); quadTo(0f, 0f, r, 0f); close()
            } else addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, Path.Direction.CW)
        }
        paint.color = if (card) 0xFFF4F4F4.toInt() else look.mediaColor
        c.drawPath(body, paint)
        val inset = w * 0.06f
        val strip = RectF(inset, inset, w - inset - (if (card) w * 0.06f else 0f), h * 0.2f)
        paint.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(strip, r * 0.4f, r * 0.4f, paint)
        logoIn(c, logo, RectF(strip.left + strip.width() * 0.08f, strip.top + strip.height() * 0.15f, strip.right - strip.width() * 0.08f, strip.bottom - strip.height() * 0.15f))
        art(c, cover, title, look, RectF(inset, h * 0.22f, w - inset, h - inset), r * 0.4f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.012f
        paint.color = 0x33000000
        c.drawPath(body, paint)
        paint.style = Paint.Style.FILL
        return out
    }

    fun darker(color: Int, f: Float) = Color.argb(Color.alpha(color), (Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

    fun isLight(color: Int) = luma(color) > 0.55f

    private fun luma(color: Int) = (0.3f * Color.red(color) + 0.59f * Color.green(color) + 0.11f * Color.blue(color)) / 255f
}
