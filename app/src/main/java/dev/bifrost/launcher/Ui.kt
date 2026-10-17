package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.io.File

/** The current theme's colors (see [Theme]). */
internal object Ui {
    val BG get() = Theme.current.bg
    val SURFACE get() = Theme.current.surface
    val ACCENT get() = Theme.current.accent
    val TEXT get() = Theme.current.text
    val DIM get() = Theme.current.dim
}

internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal fun Context.label(size: Float, color: Int = Ui.TEXT, bold: Boolean = false) = TextView(this).apply {
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** [format] null follows the system 12/24h setting. */
internal fun Context.clock(size: Float, format: String? = null, color: Int = Ui.TEXT) = TextClock(this).apply {
    textSize = size
    setTextColor(color)
    if (format != null) {
        format12Hour = format
        format24Hour = format
    }
}

internal fun Context.rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
    cornerRadius = dp(14).toFloat()
    setColor(fill)
    setStroke(dp(2), stroke)
}

/** Everything worth knowing at a glance. Refreshes itself while on screen. */
internal class StatusView(context: Context, private val multiline: Boolean, private val brief: Boolean = false) :
    TextView(context) {
    private val tick = object : Runnable {
        override fun run() {
            text = statusLines(context, brief).joinToString(if (multiline) "\n" else "   ·   ")
            postDelayed(this, 10_000)
        }
    }

    init {
        setTextColor(Ui.DIM)
        if (!multiline) {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
}

internal fun statusLines(ctx: Context, brief: Boolean): List<String> {
    val bm = ctx.getSystemService(BatteryManager::class.java)
    val battery = "🔋 ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" + if (bm.isCharging) " ⚡" else ""
    if (brief) return listOf(battery)
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val net = when {
        caps == null -> "📵"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "🖧"
        else -> "📶"
    }
    val freeGb = StatFs(Environment.getDataDirectory().path).availableBytes / 1_000_000_000
    val pads = InputDevice.getDeviceIds().asList().mapNotNull { InputDevice.getDevice(it) }
        .filter { it.isExternal && it.supportsSource(InputDevice.SOURCE_GAMEPAD) }
        .map { it.descriptor }.distinct().size
    return listOfNotNull(
        battery,
        net,
        "💾 $freeGb GB",
        if (pads > 0) "🎮 $pads" else null,
        Dock.external()?.let { "📺 ${it.mode.physicalWidth}×${it.mode.physicalHeight}" },
    )
}

/** A logo when there is one, else the text. */
internal class LogoLabel(ctx: Context, size: Float, color: Int, bold: Boolean, private val logoHeightPx: Int) : FrameLayout(ctx) {
    private val text = ctx.label(size, color, bold).apply { maxLines = 1 }
    private val image = ImageView(ctx).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
    }

    init {
        addView(text)
        addView(image, LayoutParams(LayoutParams.WRAP_CONTENT, logoHeightPx))
    }

    fun show(label: String, logo: Bitmap?) {
        text.text = label
        image.tag = null
        image.setImageBitmap(logo)
        image.visibility = if (logo != null) VISIBLE else GONE
        text.visibility = if (logo != null) INVISIBLE else VISIBLE
        contentDescription = label
    }

    fun showFile(label: String, logo: File) {
        if (!logo.exists()) return show(label, null)
        text.text = label
        text.visibility = INVISIBLE
        image.visibility = VISIBLE
        Images.load(image, logo, logoHeightPx * 4, null)
        contentDescription = label
    }
}

internal interface Tile {
    val label: String
}

/** Grid of games (box art) or Android apps (icons). */
internal class TileAdapter(private val ctx: Context) : BaseAdapter() {
    var items: List<Tile> = emptyList()
        set(value) {
            if (value === field) return
            field = value
            notifyDataSetChanged()
        }
    private val placeholder by lazy { ctx.rounded(Ui.SURFACE, Ui.SURFACE) }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tile = convertView as? LinearLayout ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10))
            addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER })
            addView(
                ctx.label(13f).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(6) },
            )
        }
        val image = tile.getChildAt(0) as ImageView
        when (val item = items[position]) {
            is Library.Game -> {
                image.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(132))
                Images.load(image, Art.game(ctx, item), ctx.dp(160), placeholder)
            }
            is Apps.Entry -> {
                image.layoutParams = LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64))
                image.tag = null
                // Own copy per screen: displays differ in density, and a shared Drawable fights over its bounds.
                image.setImageDrawable(item.icon.constantState?.newDrawable(ctx.resources)?.mutate() ?: item.icon)
            }
        }
        (tile.getChildAt(1) as TextView).text = items[position].label
        return tile
    }
}
