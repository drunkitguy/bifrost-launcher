package dev.bifrost.launcher

import android.content.Context
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

internal object Ui {
    const val BG = 0xFF0E1116.toInt()
    const val SURFACE = 0xFF1A1F27.toInt()
    const val ACCENT = 0xFF7AA2FF.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val DIM = 0xFF9AA0A6.toInt()
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
    val battery = "Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" +
        if (bm.isCharging) " (charging)" else ""
    if (brief) return listOf(battery)
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val net = when {
        caps == null -> "Offline"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        else -> "Online"
    }
    val freeGb = StatFs(Environment.getDataDirectory().path).availableBytes / 1_000_000_000
    val pads = InputDevice.getDeviceIds().asList().mapNotNull { InputDevice.getDevice(it) }
        .filter { it.isExternal && it.supportsSource(InputDevice.SOURCE_GAMEPAD) }
        .map { it.name }.distinct()
    return listOfNotNull(
        battery,
        net,
        "$freeGb GB free",
        if (pads.isEmpty()) "No external controller" else "Controller: " + pads.joinToString(", "),
        Dock.external()?.let { "Docked: ${it.name} ${it.mode.physicalWidth}×${it.mode.physicalHeight}" },
    )
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

/** The consoles (and Android apps) the library holds, Cocoon-style. */
internal class ShelfAdapter(private val ctx: Context) : BaseAdapter() {
    var shelves: List<Library.Shelf> = emptyList()

    override fun getCount() = shelves.size
    override fun getItem(position: Int) = shelves[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val card = convertView as? LinearLayout ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(64)))
            addView(ctx.label(17f, bold = true).apply { maxLines = 2 })
            addView(ctx.label(13f, Ui.DIM))
        }
        val shelf = shelves[position]
        val art = Art.system(ctx, shelf.id)
        val image = card.getChildAt(0) as ImageView
        image.visibility = if (art.exists()) View.VISIBLE else View.GONE
        if (art.exists()) Images.load(image, art, ctx.dp(200), null)
        (card.getChildAt(1) as TextView).text = shelf.title
        (card.getChildAt(2) as TextView).text = if (shelf.id == Library.APPS) "${shelf.count} apps" else "${shelf.count} games"
        val selected = shelf.id == Library.selected
        card.background = ctx.rounded(if (selected) 0x337AA2FF else Ui.SURFACE, if (selected) Ui.ACCENT else Ui.SURFACE)
        return card
    }
}
