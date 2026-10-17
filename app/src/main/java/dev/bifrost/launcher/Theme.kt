package dev.bifrost.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File

/**
 * Colors and background. A few built in; theme packs are folders in ROMs/bifrost_themes/<name>/
 * holding a theme.json ({"background": "#101418", "surface", "accent", "text", "dim", "light": false,
 * "wallpaper": "wallpaper.png"}) and optionally that image.
 */
internal class Theme(
    val name: String,
    val bg: Int,
    val surface: Int,
    val accent: Int,
    val text: Int,
    val dim: Int,
    /** Light background: logos keep their dark lettering. */
    val light: Boolean = false,
    val wallpaper: File? = null,
) {
    companion object {
        val builtIn = listOf(
            Theme("Midnight", 0xFF0E1116.toInt(), 0xFF1A1F27.toInt(), 0xFF55B8FF.toInt(), 0xFFE8EAED.toInt(), 0xFF9AA0A6.toInt()),
            Theme("Snow", 0xFFE9ECF0.toInt(), 0xFFFFFFFF.toInt(), 0xFF2FA4E7.toInt(), 0xFF1E2228.toInt(), 0xFF6B7280.toInt(), light = true),
            Theme("Neon", 0xFF2D2D2D.toInt(), 0xFF3A3A3A.toInt(), 0xFF00C3E3.toInt(), 0xFFFFFFFF.toInt(), 0xFFA8A8A8.toInt()),
            Theme("Crimson", 0xFF16080B.toInt(), 0xFF2A1015.toInt(), 0xFFE60012.toInt(), 0xFFF3E9EA.toInt(), 0xFFA88E92.toInt()),
            Theme("Ocean", 0xFF071B3F.toInt(), 0xFF0F2C5E.toInt(), 0xFF9EC6FF.toInt(), 0xFFEAF2FF.toInt(), 0xFF93A9CC.toInt()),
        )

        private const val PREF = "theme"

        @Volatile var current: Theme = builtIn[0]
            private set

        fun load(ctx: Context) {
            val name = App.prefs.getString(PREF, null)
            current = builtIn.firstOrNull { it.name == name }
                ?: runCatching { fromFolder(File(ctx.filesDir, "theme")) }.getOrNull()?.takeIf { it.name == name }
                ?: builtIn[0]
        }

        fun use(ctx: Context, theme: Theme) {
            App.prefs.edit().putString(PREF, theme.name).apply()
            current = theme
            ScreenActivity.recreateAll()
            App.changed()
        }

        /** The screen background: the pack's wallpaper, else a soft vertical gradient. */
        fun background(ctx: Context): Drawable =
            current.wallpaper?.takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.let { BitmapDrawable(ctx.resources, it) } }
                ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(current.bg, Render.darker(current.bg, if (current.light) 0.9f else 0.7f)))

        private fun fromFolder(dir: File): Theme {
            val json = JSONObject(File(dir, "theme.json").readText())
            fun color(key: String, fallback: Int) = json.optString(key).takeIf { it.isNotEmpty() }?.let(::parseHex) ?: fallback
            val base = builtIn[0]
            return Theme(
                json.optString("name").ifEmpty { dir.name },
                color("background", base.bg), color("surface", base.surface), color("accent", base.accent),
                color("text", base.text), color("dim", base.dim),
                json.optBoolean("light", false),
                json.optString("wallpaper").takeIf { it.isNotEmpty() }?.let { File(dir, File(it).name) },
            )
        }

        /** Theme packs in ROMs/bifrost_themes. Blocking (SAF queries): call off the main thread. */
        fun packs(ctx: Context): List<Pair<String, Uri>> {
            val tree = Library.tree ?: return emptyList()
            val root = Library.children(ctx, tree, DocumentsContract.getTreeDocumentId(tree)).firstOrNull { it.dir && it.name == "bifrost_themes" }
                ?: return emptyList()
            return Library.children(ctx, tree, root.id).filter { it.dir }.map { it.name to DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
        }

        /** Copies a pack into the app (so it works without the SD card) and switches to it. Blocking. */
        fun install(ctx: Context, packName: String): Theme {
            val tree = Library.tree!!
            val root = Library.children(ctx, tree, DocumentsContract.getTreeDocumentId(tree)).first { it.dir && it.name == "bifrost_themes" }
            val pack = Library.children(ctx, tree, root.id).first { it.dir && it.name == packName }
            val dir = File(ctx.filesDir, "theme").apply { deleteRecursively(); mkdirs() }
            for (file in Library.children(ctx, tree, pack.id).filter { !it.dir && it.name.matches(Regex("[\\w .-]+")) }) {
                ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, file.id))?.use { input ->
                    File(dir, file.name).outputStream().use { input.copyTo(it) }
                }
            }
            return fromFolder(dir)
        }
    }
}

/** "#RRGGBB" or "#AARRGGBB" to a color int; null if it isn't one. */
internal fun parseHex(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    val v = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> (0xFF000000 or v).toInt()
        8 -> v.toInt()
        else -> null
    }
}
