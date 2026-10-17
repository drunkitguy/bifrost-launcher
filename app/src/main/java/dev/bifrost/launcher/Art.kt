package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Box art and system art: custom picks and scraped images, stored in the app's files. */
internal object Art {
    /** Bumped whenever an image changes, so grids rebind. Main thread only. */
    var version = 0
        private set

    fun changed() {
        version++
        App.changed()
    }

    fun game(ctx: Context, g: Library.Game) = File(ctx.filesDir, "art/${g.system}/${g.baseName}.img")
    fun system(ctx: Context, id: String) = File(ctx.filesDir, "art/_systems/$id.img")

    /** Copies a picked image in as [target]. */
    fun save(ctx: Context, from: Uri, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        ctx.contentResolver.openInputStream(from)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(target)
    }.getOrDefault(false)
}

/** Decodes images off the main thread into a memory cache sized to the heap. */
internal object Images {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    val pool = Executors.newFixedThreadPool(3)

    fun load(view: ImageView, file: File, sizePx: Int, fallback: Drawable?) {
        val key = "${file.path}@${file.lastModified()}"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(fallback)
        if (!file.exists()) return
        pool.execute {
            val bitmap = decode(file, sizePx) ?: return@execute
            cache.put(key, bitmap)
            view.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Box art from the libretro thumbnail server (no account or API key needed). */
internal object Scraper {
    private const val BASE = "https://thumbnails.libretro.com/"

    /** ES-DE system name to libretro playlist name. */
    val playlists = mapOf(
        "gc" to "Nintendo - GameCube",
        "wii" to "Nintendo - Wii",
        "wiiu" to "Nintendo - Wii U",
        "n3ds" to "Nintendo - Nintendo 3DS",
        "nds" to "Nintendo - Nintendo DS",
        "n64" to "Nintendo - Nintendo 64",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "sfc" to "Nintendo - Super Nintendo Entertainment System",
        "nes" to "Nintendo - Nintendo Entertainment System",
        "famicom" to "Nintendo - Nintendo Entertainment System",
        "gba" to "Nintendo - Game Boy Advance",
        "gbc" to "Nintendo - Game Boy Color",
        "gb" to "Nintendo - Game Boy",
        "virtualboy" to "Nintendo - Virtual Boy",
        "psx" to "Sony - PlayStation",
        "ps2" to "Sony - PlayStation 2",
        "ps3" to "Sony - PlayStation 3",
        "psp" to "Sony - PlayStation Portable",
        "psvita" to "Sony - PlayStation Vita",
        "dreamcast" to "Sega - Dreamcast",
        "saturn" to "Sega - Saturn",
        "genesis" to "Sega - Mega Drive - Genesis",
        "megadrive" to "Sega - Mega Drive - Genesis",
        "segacd" to "Sega - Mega-CD - Sega CD",
        "megacd" to "Sega - Mega-CD - Sega CD",
        "sega32x" to "Sega - 32X",
        "mastersystem" to "Sega - Master System - Mark III",
        "gamegear" to "Sega - Game Gear",
        "pcengine" to "NEC - PC Engine - TurboGrafx 16",
        "tg16" to "NEC - PC Engine - TurboGrafx 16",
        "ngpc" to "SNK - Neo Geo Pocket Color",
        "atari2600" to "Atari - 2600",
        "arcade" to "FBNeo - Arcade Games",
        "fbneo" to "FBNeo - Arcade Games",
        "neogeo" to "FBNeo - Arcade Games",
        "mame" to "MAME",
    )

    private val indexes = HashMap<String, List<Pair<String, String>>>()

    /** Downloads box art for [game]. Blocking; call off the main thread. */
    fun scrape(ctx: Context, game: Library.Game): Boolean {
        val playlist = playlists[game.system] ?: return false
        val dir = BASE + playlist.replace(" ", "%20") + "/Named_Boxarts/"
        val index = synchronized(indexes) { indexes[playlist] } ?: runCatching { parseIndex(get(dir).decodeToString()) }
            .getOrNull()?.also { synchronized(indexes) { indexes[playlist] = it } } ?: return false
        val href = bestMatch(game.baseName, index) ?: return false
        val bytes = runCatching { get(dir + href) }.getOrNull() ?: return false
        val target = Art.game(ctx, game)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return true
    }

    /** Scrapes every game that has no art yet; [progress] gets "done/total". */
    fun scrapeMissing(ctx: Context, progress: (Int, Int, Int) -> Unit) {
        val todo = Library.games.values.flatten().filter { it.system in playlists && !Art.game(ctx, it).exists() }
        var found = 0
        todo.forEachIndexed { i, game ->
            if (scrape(ctx, game)) found++
            progress(i + 1, todo.size, found)
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Bifrost-Launcher")
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

/** (decoded file name, href) for every .png in a directory listing. */
internal fun parseIndex(html: String): List<Pair<String, String>> =
    Regex("href=\"([^\"?/]+\\.png)\"").findAll(html).map { m ->
        val href = m.groupValues[1]
        URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") to href
    }.distinct().toList()

/** Same title ignoring tags and punctuation; prefers the exact name, then USA, World, Europe. */
internal fun bestMatch(romBaseName: String, index: List<Pair<String, String>>): String? {
    fun key(s: String) = cleanName(s).lowercase().filter { it.isLetterOrDigit() }
    val want = key(romBaseName)
    val hits = index.filter { key(it.first.removeSuffix(".png")) == want }
    return (hits.firstOrNull { it.first.removeSuffix(".png") == romBaseName }
        ?: listOf("(USA", "(World", "(Europe").firstNotNullOfOrNull { r -> hits.firstOrNull { r in it.first } }
        ?: hits.firstOrNull())?.second
}
