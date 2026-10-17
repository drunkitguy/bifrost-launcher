package dev.bifrost.launcher

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

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

internal interface Tile {
    val label: String
}
