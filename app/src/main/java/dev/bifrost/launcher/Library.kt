package dev.bifrost.launcher

import android.app.ActivityOptions
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.widget.Toast
import java.io.File

/** The game library: ROMs found under the user's ROMs folder (Storage Access Framework tree). */
internal object Library {
    class Game(val system: String, val file: String, val docId: String) : Tile {
        val baseName = if ('.' in file) file.substringBeforeLast('.') else file
        override val label = cleanName(baseName)
    }

    class Shelf(val id: String, val title: String, val count: Int)

    const val APPS = "apps"
    const val RECENT = "recent"
    private const val TREE = "rom_tree"
    private const val SHELF = "shelf"

    var games: Map<String, List<Game>> = emptyMap()
        private set
    var scanning = false
        private set

    val tree: Uri? get() = App.prefs.getString(TREE, null)?.let(Uri::parse)

    /** Browse the discs and cartridges themselves instead of their cases. */
    var mediaView: Boolean
        get() = App.prefs.getBoolean("media_view", false)
        set(value) {
            App.prefs.edit().putBoolean("media_view", value).apply()
            App.changed()
        }

    /** Inside a console's shelf (true) or on the home row of consoles (false). Back goes up. */
    var inside = false
        set(value) {
            field = value
            App.changed()
        }

    /** The game under the cursor, for the details on the other screen. */
    var highlight: Game? = null
        set(value) {
            if (value === field) return
            field = value
            App.main.post { App.changed() } // set from inside a refresh: don't recurse into it
        }

    /** Recently played games, newest first. Same list instance until it changes. */
    var recent: List<Game> = emptyList()
        private set

    var selected: String
        get() = App.prefs.getString(SHELF, null)?.takeIf { it == APPS || it in games || (it == RECENT && recent.isNotEmpty()) }
            ?: shelves().first().id
        set(value) {
            App.prefs.edit().putString(SHELF, value).apply()
            App.changed()
        }

    fun init(ctx: Context) {
        games = load(ctx)
        updateRecent()
        rescan(ctx)
    }

    fun setTree(ctx: Context, uri: Uri) {
        App.prefs.edit().putString(TREE, uri.toString()).apply()
        rescan(ctx)
    }

    fun shelves(): List<Shelf> =
        listOfNotNull(recent.takeIf { it.isNotEmpty() }?.let { Shelf(RECENT, title(RECENT), it.size) }) +
            games.map { (id, list) -> Shelf(id, Systems.byName[id]?.fullname ?: id, list.size) }.sortedBy { it.title } +
            Shelf(APPS, title(APPS), Apps.list.size)

    fun title(id: String) = when (id) {
        APPS -> "Android apps"
        RECENT -> "Recently played"
        else -> Systems.byName[id]?.fullname ?: id
    }

    fun tiles(): List<Tile> = selected.let { if (it == APPS) Apps.list else gamesOf(it) }

    fun gamesOf(id: String): List<Game> = if (id == RECENT) recent else games[id].orEmpty()

    // ── play sessions: like Cocoon, time from launch until Bifrost is back ──

    private const val PLAYING = "playing"

    private fun startSession(game: Game) =
        App.prefs.edit().putString(PLAYING, game.docId).putLong("$PLAYING.at", System.currentTimeMillis()).apply()

    /** Ends the running session, if any. Called whenever a Bifrost screen comes back. */
    fun endSession() {
        val docId = App.prefs.getString(PLAYING, null) ?: return
        val at = App.prefs.getLong("$PLAYING.at", 0L)
        App.prefs.edit()
            .putLong("time.$docId", App.prefs.getLong("time.$docId", 0L) + sessionLength(at, System.currentTimeMillis()))
            .putLong("last.$docId", at)
            .remove(PLAYING).remove("$PLAYING.at")
            .apply()
        updateRecent()
        App.changed()
    }

    fun playTime(game: Game) = App.prefs.getLong("time.${game.docId}", 0L)
    fun lastPlayed(game: Game) = App.prefs.getLong("last.${game.docId}", 0L)

    private fun updateRecent() {
        recent = games.values.flatten().filter { lastPlayed(it) > 0 }.sortedByDescending { lastPlayed(it) }.take(15)
    }

    /** L1/R1: previous/next shelf. */
    fun cycle(step: Int) {
        val ids = shelves().map { it.id }
        selected = ids[(ids.indexOf(selected) + step).mod(ids.size)]
    }

    fun rescan(ctx: Context) {
        if (scanning) return
        scanning = true
        App.changed()
        val app = ctx.applicationContext
        Thread {
            val found = runCatching { scan(app) }.getOrNull()
            App.main.post {
                scanning = false
                if (found != null) {
                    games = found
                    save(app, found)
                    updateRecent()
                }
                App.changed()
            }
        }.start()
    }

    // ── scanning ─────────────────────────────────────────────────────────

    private class Doc(val id: String, val name: String, val dir: Boolean)

    private fun children(ctx: Context, tree: Uri, docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return ctx.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Doc(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR)) }
        }.orEmpty()
    }

    private fun scan(ctx: Context): Map<String, List<Game>> {
        val tree = tree ?: run {
            Systems.load(ctx)
            return emptyMap()
        }
        val root = children(ctx, tree, DocumentsContract.getTreeDocumentId(tree))
        fun open(name: String) = root.firstOrNull { it.name == name }
            ?.let { ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, it.id)) }
        Systems.load(ctx, open("bifrost_systems.xml"), open("bifrost_find_rules.xml"))

        val out = LinkedHashMap<String, List<Game>>()
        for (folder in root.filter { it.dir }) {
            val system = Systems.forFolder(folder.name) ?: continue
            val found = mutableListOf<Game>()
            fun walk(docId: String, depth: Int) {
                val docs = children(ctx, tree, docId)
                val names = visibleGames(docs.map { it.name }, system.extensions).toSet()
                for (d in docs) {
                    when {
                        d.name in names -> found += Game(system.name, d.name, d.id) // files, and dirs like "Game.ps3"
                        d.dir && depth < 3 && !d.name.startsWith('.') -> walk(d.id, depth + 1)
                    }
                }
            }
            walk(folder.id, 0)
            if (found.isNotEmpty()) out[system.name] = found.sortedBy { it.label.lowercase() }
        }
        return out
    }

    // ── cache, so the library shows instantly while a rescan runs ────────

    private fun cache(ctx: Context) = File(ctx.filesDir, "library.tsv")

    private fun save(ctx: Context, games: Map<String, List<Game>>) = runCatching {
        cache(ctx).writeText(games.values.flatten().joinToString("\n") { "${it.system}\t${it.file}\t${it.docId}" })
    }

    private fun load(ctx: Context): Map<String, List<Game>> = runCatching {
        cache(ctx).readLines().mapNotNull { line ->
            line.split('\t').takeIf { it.size == 3 }?.let { Game(it[0], it[1], it[2]) }
        }.groupBy { it.system }
    }.getOrDefault(emptyMap())

    // ── launching ────────────────────────────────────────────────────────

    private fun emuKey(system: String) = "emu.$system"
    private fun gameEmuKey(game: Game) = "emu.game.${game.docId}"

    /** Commands for [system] whose emulator is installed, with their launch plans for [game]. */
    fun available(ctx: Context, system: String, game: Game? = null): List<Pair<Systems.Command, Launch.Plan>> {
        Systems.ensureLoaded(ctx)
        val sys = Systems.byName[system] ?: return emptyList()
        val vars = game?.let { vars(ctx, it) } ?: Launch.Vars("/x/x", "content://x", "x", "/x", { Systems.emulator(ctx.packageManager, it)?.packageName }, { "0" })
        return sys.commands.mapNotNull { c -> Launch.plan(c.raw, vars)?.let { c to it } }
    }

    /** Per-game choice, else per-system choice, else the first installed standalone emulator. */
    fun chosen(ctx: Context, game: Game): Pair<Systems.Command, Launch.Plan>? {
        val options = available(ctx, game.system, game)
        val pick = App.prefs.getString(gameEmuKey(game), null) ?: App.prefs.getString(emuKey(game.system), null)
        return options.firstOrNull { it.first.label == pick }
            ?: options.firstOrNull { "Standalone" in it.first.label }
            ?: options.firstOrNull()
    }

    fun systemChoice(system: String) = App.prefs.getString(emuKey(system), null)
    fun gameChoice(game: Game) = App.prefs.getString(gameEmuKey(game), null)

    fun setSystemChoice(system: String, label: String?) = App.prefs.edit().putString(emuKey(system), label).apply()
    fun setGameChoice(game: Game, label: String?) = App.prefs.edit().putString(gameEmuKey(game), label).apply()

    fun uri(game: Game): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, game.docId)

    private fun vars(ctx: Context, game: Game): Launch.Vars {
        val uri = uri(game)
        val rootId = tree?.let(DocumentsContract::getTreeDocumentId).orEmpty()
        return Launch.Vars(
            path = docIdToPath(game.docId),
            uri = uri.toString(),
            baseName = game.baseName,
            romRoot = docIdToPath(rootId),
            pkg = { Systems.emulator(ctx.packageManager, it)?.packageName },
            inject = {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        ByteArray(4096).let { b -> String(b, 0, input.read(b).coerceAtLeast(0)) }
                    }
                }.getOrNull()
            },
        )
    }

    fun launch(ctx: Context, game: Game, displayId: Int) {
        val (command, plan) = chosen(ctx, game) ?: run {
            val names = Systems.byName[game.system]?.commands?.map { it.label }?.take(4)?.joinToString(", ").orEmpty()
            Toast.makeText(ctx, "No emulator installed for ${title(game.system)}. Try: $names", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent().apply {
            component = Systems.emulator(ctx.packageManager, plan.emulator)
            plan.action?.let { action = it }
            plan.categories.forEach(::addCategory)
            val data = plan.data?.let(Uri::parse)
            if (plan.mimeType != null) setDataAndType(data, plan.mimeType) else this.data = data
            for ((key, value) in plan.extras) when (value) {
                is Boolean -> putExtra(key, value)
                is Int -> putExtra(key, value)
                is Array<*> -> putExtra(key, value.map { it.toString() }.toTypedArray())
                else -> putExtra(key, value.toString())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (plan.clearTask) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (plan.clearTop) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (plan.grantsUri) {
                // The ROM is inside our granted folder, so we can lend the emulator read access to it.
                clipData = ClipData.newRawUri(game.label, uri(game))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        try {
            try {
                ctx.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            } catch (e: SecurityException) {
                ctx.startActivity(intent) // display refused: let Android pick
            }
            startSession(game)
        } catch (e: Exception) {
            Toast.makeText(ctx, "${command.label} couldn't start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

/** One session's length: never negative, at most 12 hours (a game left open overnight). */
internal fun sessionLength(start: Long, end: Long) = (end - start).coerceIn(0L, 12 * 3_600_000L)

/** "3h 20m", "45m", "Under a minute". */
internal fun playTimeText(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "Under a minute"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

/** "Today", "Yesterday", "3 days ago". */
internal fun agoText(then: Long, now: Long): String {
    val days = ((now - then) / 86_400_000L).coerceAtLeast(0)
    return when (days) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> "$days days ago"
    }
}

/** SAF document id ("primary:ROMs/gc/x.rvz", "1234-ABCD:ROMs/x") to a filesystem path. */
internal fun docIdToPath(docId: String): String {
    val volume = docId.substringBefore(':')
    val rel = docId.substringAfter(':', "")
    val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
    return if (rel.isEmpty()) base else "$base/$rel"
}

/** "Super Mario Sunshine (USA) [!]" -> "Super Mario Sunshine". */
internal fun cleanName(baseName: String): String =
    baseName.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().ifEmpty { baseName }

/**
 * Files in one folder that are games for a system with [extensions]. Hides the pieces of
 * multi-file games: .bin/.img/.sub tracks next to a .cue/.gdi/.ccd/.toc, and discs named after an .m3u.
 */
internal fun visibleGames(names: List<String>, extensions: Set<String>): List<String> {
    fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    val candidates = names.filter { ext(it) in extensions }
    val hasSheet = names.any { ext(it) in setOf("cue", "gdi", "ccd", "toc") }
    val playlists = candidates.filter { ext(it) == "m3u" }.map { it.substringBeforeLast('.') }
    return candidates.filter { n ->
        val e = ext(n)
        !(hasSheet && e in setOf("bin", "img", "sub", "raw")) &&
            !(e != "m3u" && playlists.any { n.startsWith(it) })
    }
}
