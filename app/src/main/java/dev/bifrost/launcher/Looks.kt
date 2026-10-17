package dev.bifrost.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
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
            "gc" to Look(0.71f, BLACK, true, media = Media.MINI_DISC, mediaColor = 0xFFD8DCE2.toInt(), console = Console.CUBE, consoleColor = 0xFF4B3F8F.toInt()),
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

/** Draws cases, media and consoles. Everything is code-drawn: no trademarked images ship. */
internal object Render {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Case width for [h], following the cover art's shape when there is art. */
    fun caseWidth(look: Look, cover: Bitmap?, h: Int, withSpine: Boolean = true): Int {
        val ratio = cover?.let { (it.width.toFloat() / it.height).coerceIn(0.5f, 1.6f) } ?: look.ratio
        val spine = if (look.plastic && withSpine) 0.07f else 0f
        return (h * ratio * (1 + spine)).toInt().coerceAtLeast(1)
    }

    /** The case, standing on a glossy floor: [h] tall, then a fading reflection [reflect] tall. */
    /** The case's front. [withSpine] false when a 3D box draws the spine as its own face. */
    fun case(look: Look, cover: Bitmap?, title: String, h: Int, reflect: Int = 0, withSpine: Boolean = true): Bitmap {
        val w = caseWidth(look, cover, h, withSpine)
        val out = Bitmap.createBitmap(w, h + reflect, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawCase(c, look, cover, title, w, h, withSpine)
        if (reflect > 0) {
            val flip = Matrix().apply { setScale(1f, -1f); postTranslate(0f, 2f * h) }
            c.save()
            c.clipRect(0, h, w, h + reflect)
            c.drawBitmap(out, flip, paint)
            c.restore()
            val fade = Paint().apply {
                shader = LinearGradient(0f, h.toFloat(), 0f, (h + reflect).toFloat(), 0x55000000, 0, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            c.drawRect(0f, h.toFloat(), w.toFloat(), (h + reflect).toFloat(), fade)
        }
        return out
    }

    private fun drawCase(c: Canvas, look: Look, cover: Bitmap?, title: String, w: Int, h: Int, withSpine: Boolean) {
        val r = w * 0.03f
        val body = RectF(0f, 0f, w.toFloat(), h.toFloat())
        if (!look.plastic) {
            // Cardboard box: the art is the box; a printed edge and a little wear.
            art(c, cover, title, look, body, r * 0.4f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.012f
            paint.color = 0x55000000
            c.drawRoundRect(body, r * 0.4f, r * 0.4f, paint)
            paint.style = Paint.Style.FILL
            gloss(c, body, 0x26)
            return
        }
        paint.color = look.caseColor
        c.drawRoundRect(body, r, r, paint)
        // Spine with grip ridges.
        val spine = if (withSpine) w * 0.065f else 0f
        if (withSpine) {
            paint.shader = LinearGradient(0f, 0f, spine, 0f, darker(look.caseColor, 0.55f), look.caseColor, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 0f, spine + r, h.toFloat()), r, r, paint)
            paint.shader = null
            paint.color = darker(look.caseColor, 0.4f)
            val ridges = 3
            for (i in 1..ridges) {
                val x = spine * i / (ridges + 1)
                c.drawRect(x - 0.6f, h * 0.06f, x + 0.6f, h * 0.94f, paint)
            }
        }
        // Cover under the clear sleeve, with an optional top banner.
        val pad = w * 0.035f
        val sleeve = RectF(spine + pad * 0.6f, pad, w - pad, h - pad)
        val banner = look.banner
        val artRect = if (banner != null) {
            val bh = sleeve.height() * 0.085f
            paint.color = banner
            c.drawRect(sleeve.left, sleeve.top, sleeve.right, sleeve.top + bh, paint)
            look.bannerText?.let { text ->
                paint.color = if (luma(banner) > 0.6f) 0xFF55585F.toInt() else Color.WHITE
                paint.textSize = bh * 0.62f
                paint.isFakeBoldText = true
                c.drawText(text, sleeve.left + bh * 0.4f, sleeve.top + bh * 0.76f, paint)
                paint.isFakeBoldText = false
            }
            RectF(sleeve.left, sleeve.top + bh, sleeve.right, sleeve.bottom)
        } else sleeve
        art(c, cover, title, look, artRect, 0f)
        gloss(c, sleeve, 0x40)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.006f
        paint.color = 0x33FFFFFF
        c.drawRoundRect(body, r, r, paint)
        paint.style = Paint.Style.FILL
    }

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
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(5).build()
            c.translate(dst.left + dst.width() * 0.08f, dst.centerY() - layout.height / 2f)
            layout.draw(c)
        }
        c.restore()
    }

    private fun gloss(c: Canvas, r: RectF, alpha: Int) {
        paint.shader = LinearGradient(r.left, r.top, r.left + r.width() * 0.7f, r.top + r.height() * 0.5f,
            Color.argb(alpha, 255, 255, 255), 0, Shader.TileMode.CLAMP)
        c.drawRect(r, paint)
        paint.shader = null
    }

    /**
     * A case's spine as it stands on a shelf: [w] wide, [h] tall. System name at the top,
     * title running down, and for Wii the colored corner.
     */
    fun spine(look: Look, title: String, label: String, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w.coerceAtLeast(2), h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = if (look.plastic) look.caseColor else darker(look.accent, 0.55f)
        // Rounded plastic: lighter in the middle, darker at both edges.
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(darker(bg, 0.7f), bg, bg, darker(bg, 0.62f)),
            floatArrayOf(0f, 0.25f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        val bannerLen = h * 0.12f
        look.banner?.takeIf { it != bg }?.let {
            paint.color = it
            c.drawRect(0f, 0f, w.toFloat(), bannerLen, paint)
        }
        if (look.cornerStripe) {
            paint.color = stripeColors[(title.hashCode() and 0x7fffffff) % stripeColors.size]
            c.drawPath(Path().apply { moveTo(0f, h.toFloat()); lineTo(w.toFloat(), h - w * 1.1f); lineTo(w.toFloat(), h.toFloat()); close() }, paint)
        }
        val ink = if (luma(look.banner ?: bg) > 0.55f) 0xFF4A4D55.toInt() else Color.WHITE
        val titleInk = if (luma(bg) > 0.55f) 0xFF15161A.toInt() else Color.WHITE
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        // Both read top to bottom, like real spines.
        c.save()
        c.rotate(90f)
        text.color = ink
        text.textSize = w * 0.5f
        text.isFakeBoldText = true
        c.drawText(label, h * 0.025f, -w * 0.32f, text)
        text.color = titleInk
        text.textSize = w * 0.42f
        val room = h * 0.8f - bannerLen
        val shown = android.text.TextUtils.ellipsize(title.uppercase(), text, room, android.text.TextUtils.TruncateAt.END)
        c.drawText(shown, 0, shown.length, bannerLen + h * 0.03f, -w * 0.34f, text)
        c.restore()
        return out
    }

    private val stripeColors = intArrayOf(0xFF2E9E4F.toInt(), 0xFFC8343C.toInt(), 0xFF2F7FD1.toInt(), 0xFFE5C21C.toInt(), 0xFF8C3E2E.toInt())

    /** The disc, cartridge or card, with the cover art as its label. [size] is its longest side. */
    fun media(look: Look, cover: Bitmap?, title: String, size: Int): Bitmap {
        return when (look.media) {
            Look.Media.DISC, Look.Media.MINI_DISC -> disc(look, cover, title, size)
            Look.Media.UMD -> umd(look, cover, title, size)
            Look.Media.CART -> cart(look, cover, title, (size * 0.95f).toInt(), (size * 0.75f).toInt(), grip = true)
            Look.Media.CART_SMALL -> cart(look, cover, title, (size * 0.62f).toInt(), (size * 0.56f).toInt(), grip = true)
            Look.Media.CARD -> cart(look, cover, title, (size * 0.5f).toInt(), (size * 0.58f).toInt(), grip = false)
        }
    }

    private fun disc(look: Look, cover: Bitmap?, title: String, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cx = size / 2f
        val r = size / 2f
        paint.shader = RadialGradient(cx, cx, r, intArrayOf(0xFFE8EBEF.toInt(), look.mediaColor, darker(look.mediaColor, 0.75f)),
            floatArrayOf(0.2f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r, paint)
        paint.shader = null
        // Label: the cover art printed on the disc, between hub and rim.
        val label = Path().apply {
            addCircle(cx, cx, r * 0.93f, Path.Direction.CW)
            addCircle(cx, cx, r * 0.36f, Path.Direction.CCW)
        }
        c.save()
        c.clipPath(label)
        art(c, cover, title, look, RectF(0f, 0f, size.toFloat(), size.toFloat()), 0f)
        c.restore()
        // Rainbow sheen and the clear hub.
        paint.shader = SweepGradient(cx, cx, intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF, 0x2000E5FF, 0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF), null)
        c.drawCircle(cx, cx, r, paint)
        paint.shader = null
        paint.color = 0xCCD9DDE3.toInt()
        c.drawCircle(cx, cx, r * 0.33f, paint)
        paint.color = 0x66FFFFFF
        c.drawCircle(cx, cx, r * 0.2f, paint)
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

    private fun cart(look: Look, cover: Bitmap?, title: String, w: Int, h: Int, grip: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = min(w, h) * 0.06f
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), look.mediaColor, darker(look.mediaColor, 0.8f), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint)
        paint.shader = null
        if (grip) {
            paint.color = darker(look.mediaColor, 0.7f)
            for (i in 0 until 5) {
                val y = h * (0.05f + i * 0.035f)
                c.drawRect(w * 0.12f, y, w * 0.88f, y + h * 0.012f, paint)
            }
        }
        val labelTop = if (grip) h * 0.25f else h * 0.08f
        art(c, cover, title, look, RectF(w * 0.1f, labelTop, w * 0.9f, h * 0.84f), r * 0.5f)
        // Connector edge.
        paint.color = if (grip) darker(look.mediaColor, 0.6f) else 0xFFC9A64A.toInt()
        c.drawRect(w * 0.14f, h * 0.9f, w * 0.86f, h * 0.97f, paint)
        return out
    }

    /**
     * A stylized console filling [box]. Returns the slot line (left, y, right): media drops in there.
     * [lit] 0..1 turns the power light on.
     */
    fun console(c: Canvas, look: Look, box: RectF, lit: Float): RectF {
        val col = look.consoleColor
        val shade = darker(col, 0.72f)
        paint.shader = LinearGradient(0f, box.top, 0f, box.bottom, col, shade, Shader.TileMode.CLAMP)
        val slot: RectF
        when (look.console) {
            Look.Console.CUBE -> {
                val s = min(box.width(), box.height())
                val body = RectF(box.centerX() - s / 2, box.bottom - s * 0.8f, box.centerX() + s / 2, box.bottom)
                c.drawRoundRect(body, s * 0.08f, s * 0.08f, paint)
                paint.shader = null
                // Open lid, tilted back.
                paint.color = darker(col, 0.85f)
                c.drawPath(Path().apply {
                    moveTo(body.left + s * 0.1f, body.top); lineTo(body.right - s * 0.1f, body.top)
                    lineTo(body.right - s * 0.18f, body.top - s * 0.18f); lineTo(body.left + s * 0.18f, body.top - s * 0.18f); close()
                }, paint)
                slot = RectF(body.left + s * 0.18f, body.top + s * 0.02f, body.right - s * 0.18f, body.top + s * 0.02f)
            }
            Look.Console.SLIM, Look.Console.TOP_SLOT -> {
                val h = box.width() * if (look.console == Look.Console.SLIM) 0.24f else 0.36f
                val body = RectF(box.left, box.bottom - h, box.right, box.bottom)
                c.drawRoundRect(body, h * 0.15f, h * 0.15f, paint)
                paint.shader = null
                paint.color = darker(col, 0.5f)
                val slotW = box.width() * 0.5f
                c.drawRoundRect(RectF(body.centerX() - slotW / 2, body.top + h * 0.12f, body.centerX() + slotW / 2, body.top + h * 0.2f),
                    h * 0.04f, h * 0.04f, paint)
                slot = RectF(body.centerX() - slotW / 2, body.top + h * 0.16f, body.centerX() + slotW / 2, body.top + h * 0.16f)
            }
            Look.Console.HANDHELD, Look.Console.TABLET -> {
                val h = box.width() * 0.48f
                val body = RectF(box.left, box.bottom - h, box.right, box.bottom)
                c.drawRoundRect(body, h * 0.22f, h * 0.22f, paint)
                paint.shader = null
                if (look.console == Look.Console.TABLET) {
                    paint.color = 0xFF00A6D6.toInt()
                    c.drawRoundRect(RectF(body.left, body.top, body.left + h * 0.32f, body.bottom), h * 0.18f, h * 0.18f, paint)
                    paint.color = 0xFFE4002B.toInt()
                    c.drawRoundRect(RectF(body.right - h * 0.32f, body.top, body.right, body.bottom), h * 0.18f, h * 0.18f, paint)
                }
                paint.color = 0xFF0B0C0E.toInt()
                val inset = h * if (look.console == Look.Console.TABLET) 0.38f else 0.3f
                c.drawRoundRect(RectF(body.left + inset, body.top + h * 0.14f, body.right - inset, body.bottom - h * 0.14f), h * 0.04f, h * 0.04f, paint)
                slot = RectF(body.centerX() - box.width() * 0.22f, body.top, body.centerX() + box.width() * 0.22f, body.top)
            }
        }
        // Power light.
        val ledX = box.right - box.width() * 0.08f
        val ledY = box.bottom - box.width() * 0.05f
        paint.color = darker(0xFF3BD16F.toInt(), 0.25f + 0.75f * lit)
        c.drawCircle(ledX, ledY, box.width() * 0.012f, paint)
        if (lit > 0f) {
            paint.shader = RadialGradient(ledX, ledY, box.width() * 0.05f, Color.argb((120 * lit).toInt(), 59, 209, 111), 0, Shader.TileMode.CLAMP)
            c.drawCircle(ledX, ledY, box.width() * 0.05f, paint)
            paint.shader = null
        }
        return slot
    }

    fun darker(color: Int, f: Float) = Color.argb(Color.alpha(color), (Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

    private fun luma(color: Int) = (0.3f * Color.red(color) + 0.59f * Color.green(color) + 0.11f * Color.blue(color)) / 255f
}
