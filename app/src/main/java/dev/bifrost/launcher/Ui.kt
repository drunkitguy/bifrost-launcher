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

internal class AppAdapter(private val ctx: Context) : BaseAdapter() {
    var apps: List<Apps.Entry> = emptyList()
        set(value) {
            if (value === field) return
            field = value
            notifyDataSetChanged()
        }

    override fun getCount() = apps.size
    override fun getItem(position: Int) = apps[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tile = convertView as? LinearLayout ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(12))
            addView(ImageView(ctx), LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)))
            addView(
                ctx.label(13f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(6) },
            )
        }
        val app = apps[position]
        // Own copy per screen: displays differ in density, and a shared Drawable fights over its bounds.
        val icon = app.icon.constantState?.newDrawable(ctx.resources)?.mutate() ?: app.icon
        (tile.getChildAt(0) as ImageView).setImageDrawable(icon)
        (tile.getChildAt(1) as TextView).text = app.label
        return tile
    }
}
