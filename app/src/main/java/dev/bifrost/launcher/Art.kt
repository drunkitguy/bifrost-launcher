package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
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
    fun gameLogo(ctx: Context, g: Library.Game) = File(ctx.filesDir, "art/${g.system}/${g.baseName}.logo")
    fun title(ctx: Context, g: Library.Game) = File(ctx.filesDir, "art/${g.system}/${g.baseName}.title")
    fun hero(ctx: Context, g: Library.Game) = File(ctx.filesDir, "art/${g.system}/${g.baseName}.hero")
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
        Art.hero(ctx, game).let { if (!it.exists()) SteamGridDb.hero(game, it) }
        val playlist = playlists[game.system] ?: return Art.hero(ctx, game).exists()
        val box = Art.game(ctx, game)
        if (!box.exists()) fetch(playlist, "Named_Boxarts", game, box)
        Art.gameLogo(ctx, game).let { if (!it.exists()) fetch(playlist, "Named_Logos", game, it) }
        Art.title(ctx, game).let { if (!it.exists()) fetch(playlist, "Named_Titles", game, it) }
        return box.exists()
    }

    private fun fetch(playlist: String, folder: String, game: Library.Game, target: File): Boolean {
        val dir = BASE + playlist.replace(" ", "%20") + "/$folder/"
        // A failed listing is remembered as empty, so a missing folder costs one request, not one per game.
        val index = synchronized(indexes) { indexes[dir] }
            ?: (runCatching { parseIndex(get(dir).decodeToString()) }.getOrNull() ?: emptyList()).also { synchronized(indexes) { indexes[dir] = it } }
        val href = bestMatch(game.baseName, index) ?: return false
        val bytes = runCatching { get(dir + href) }.getOrNull() ?: return false
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return true
    }

    /** Scrapes box art and logos for every game missing either; [progress] gets done, total, box art found. */
    fun scrapeMissing(ctx: Context, progress: (Int, Int, Int) -> Unit) {
        val todo = Library.games.values.flatten().filter {
            (it.system in playlists && (!Art.game(ctx, it).exists() || !Art.gameLogo(ctx, it).exists() || !Art.title(ctx, it).exists())) ||
                (SteamGridDb.key.isNotEmpty() && !Art.hero(ctx, it).exists())
        }
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

/**
 * Wide fan art ("heroes") from SteamGridDB, like Cocoon's top screen. Needs the user's own free
 * API key (Settings), so it is skipped without one.
 */
internal object SteamGridDb {
    private const val API = "https://www.steamgriddb.com/api/v2/"

    var key: String
        get() = App.prefs.getString("sgdb_key", "").orEmpty()
        set(value) = App.prefs.edit().putString("sgdb_key", value.trim()).apply()

    fun hero(game: Library.Game, target: File): Boolean {
        val key = key.takeIf { it.isNotEmpty() } ?: return false
        return runCatching {
            val search = org.json.JSONObject(get(API + "search/autocomplete/" + java.net.URLEncoder.encode(game.label, "UTF-8").replace("+", "%20"), key))
            val id = search.getJSONArray("data").getJSONObject(0).getLong("id")
            val heroes = org.json.JSONObject(get(API + "heroes/game/$id", key)).getJSONArray("data")
            val url = heroes.getJSONObject(0).getString("url")
            target.parentFile?.mkdirs()
            target.writeBytes(URL(url).readBytes())
            true
        }.getOrDefault(false)
    }

    private fun get(url: String, key: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Authorization", "Bearer $key")
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { it.readBytes().decodeToString() }
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
