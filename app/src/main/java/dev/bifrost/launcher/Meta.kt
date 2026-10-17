package dev.bifrost.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Release year and description for a game, from Wikipedia (free, no account; text CC BY-SA, so
 * the screen credits it). Fetched when a game is first highlighted, then kept on the device.
 */
internal object Meta {
    class Info(val year: String?, val description: String?)

    private const val AGENT = "BifrostLauncher/0.6 (https://github.com/drunkitguy/bifrost-launcher)"
    private val memory = HashMap<String, Info?>()
    private val pending = HashSet<String>()

    private fun file(ctx: Context, g: Library.Game) = File(ctx.filesDir, "meta/${g.system}/${g.baseName}.json")

    /** Cached info, or null; a first call starts the lookup and [onReady] fires when it lands. */
    fun get(ctx: Context, game: Library.Game, onReady: () -> Unit): Info? {
        memory[game.docId]?.let { return it }
        if (game.docId in memory || !pending.add(game.docId)) return null
        val app = ctx.applicationContext
        Images.pool.execute {
            val f = file(app, game)
            val info = runCatching {
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    f.writeText((lookup(game) ?: JSONObject().put("none", true)).toString())
                }
                JSONObject(f.readText()).takeIf { !it.optBoolean("none") }
                    ?.let { Info(it.optString("year").ifEmpty { null }, it.optString("description").ifEmpty { null }) }
            }.getOrNull()
            App.main.post {
                pending.remove(game.docId)
                memory[game.docId] = info
                if (info != null) onReady()
            }
        }
        return null
    }

    private fun lookup(game: Library.Game): JSONObject? {
        val system = Library.title(game.system).substringAfter(' ')
        val query = URLEncoder.encode("${game.label} $system video game", "UTF-8")
        val search = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=6&srsearch=$query"))
        val results = search.getJSONObject("query").getJSONArray("search")
        val titles = (0 until results.length()).map { results.getJSONObject(it).getString("title") }
        val title = pickTitle(game.label, titles) ?: return null
        val summary = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8")))
        val kind = summary.optString("description")
        if ("game" !in kind.lowercase()) return null // not a video game article
        return JSONObject()
            .put("year", yearIn(kind) ?: yearIn(summary.optString("extract").substringBefore(". ")))
            .put("description", summary.optString("extract"))
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", AGENT)
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}

/** The search result that is this game: same name (ignoring punctuation and a "(video game)" tag). */
internal fun pickTitle(game: String, titles: List<String>): String? {
    fun key(s: String) = cleanName(s).lowercase().filter { it.isLetterOrDigit() }
    val want = key(game)
    if (want.isEmpty()) return null
    return titles.firstOrNull { key(it) == want } ?: titles.firstOrNull { key(it).startsWith(want) && key(it).length - want.length <= 12 }
}

/** First plausible release year in [text]: "2002 video game" -> "2002". */
internal fun yearIn(text: String): String? = Regex("\\b(19[7-9]\\d|20[0-4]\\d)\\b").find(text)?.value
