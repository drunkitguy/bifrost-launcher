package dev.bifrost.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * One class for every screen; the layout follows the display it lands on:
 * TOP (display 0, via [HomeActivity]): the selected console's games. BOTTOM (built-in second
 * panel): the consoles. DOCK (external display): both, while the built-in screens go dark.
 */
open class ScreenActivity : Activity() {
    private enum class Role { TOP, BOTTOM, DOCK }

    private lateinit var role: Role
    private lateinit var cover: View
    private var displayId = Display.INVALID_DISPLAY
    private var grid: GridView? = null
    private var shelf: ShelfView? = null
    private lateinit var insert: InsertView
    private var shelfGrid: GridView? = null
    private var title: LogoLabel? = null
    private var shelfTitle: LogoLabel? = null
    private var dark: Boolean? = null
    private var artVersion = -1
    private var pendingArt: File? = null
    private val tiles = TileAdapter(this)
    private val shelves = ShelfAdapter(this)
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayId = display!!.displayId
        // A dock screen that Android moved onto the top screen after the cable came out.
        if (this !is HomeActivity && displayId == Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        role = when {
            this is HomeActivity -> Role.TOP
            Dock.isExternal(display!!) -> Role.DOCK
            else -> Role.BOTTOM
        }
        if (this !is HomeActivity) {
            open[displayId] = this
            starting.remove(displayId)
        }
        cover = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true // swallow touches on a dark screen
            visibility = View.GONE
        }
        val content = when (role) {
            Role.TOP -> topLayout()
            Role.BOTTOM -> bottomLayout()
            Role.DOCK -> dockLayout()
        }
        shelves.onArt = { shelves.notifyDataSetChanged() }
        insert = InsertView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(content)
            addView(insert)
            addView(cover)
        })
        (shelf ?: shelfGrid)?.requestFocus()
        App.listeners += listener
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::insert.isInitialized) insert.stop()
    }

    override fun onDestroy() {
        App.listeners -= listener
        if (open[displayId] === this) open.remove(displayId)
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = Unit // home screens stay put

    private fun refresh() {
        if (isFinishing) return
        if (role == Role.DOCK && !Dock.docked) {
            finish()
            return
        }
        val apps = Library.selected == Library.APPS
        val items = Library.tiles()
        grid?.visibility = if (apps) View.VISIBLE else View.GONE
        shelf?.visibility = if (apps) View.GONE else View.VISIBLE
        if (apps) {
            if (items !== tiles.items) {
                tiles.items = items
                grid?.setSelection(0)
                title?.show(items.firstOrNull()?.label.orEmpty(), null)
            }
        } else {
            shelf?.games = Library.games[Library.selected].orEmpty()
            if (artVersion != Art.version) shelf?.invalidate()
        }
        if (items.isEmpty()) title?.show(emptyHint(), null)
        if (grid?.hasFocus() == true && !apps) shelf?.requestFocus()
        if (shelf?.hasFocus() == true && apps) grid?.requestFocus()
        artVersion = Art.version
        shelves.shelves = Library.shelves()
        shelves.notifyDataSetChanged()
        shelfTitle?.show(Library.title(Library.selected), Logos.get(this, Library.selected, Logos.Kind.LOGO, dp(26)) { refresh() })
        shelf?.media = Library.mediaView
        if (!apps) insert.post { insert.prefetch(Library.selected) }

        val off = role != Role.DOCK && Dock.docked
        if (off == dark) return
        dark = off
        cover.visibility = if (off) View.VISIBLE else View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = if (off) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        // Not focusable while dark, so a stray touch here can't pull the controller off the TV.
        if (off) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.insetsController?.let {
            if (off) it.hide(WindowInsets.Type.systemBars()) else it.show(WindowInsets.Type.systemBars())
        }
    }

    private fun emptyHint() = when {
        Library.tree == null -> "Choose your ROMs folder in Bifrost settings"
        Library.scanning -> "Looking for games…"
        else -> "Nothing here"
    }

    /** Home pressed (or the top screen came back): docked, Home means the external screen. */
    protected fun goHome() {
        val ext = Dock.external()
        if (ext != null) {
            show(this, ext.displayId)
            return
        }
        val bottom = Dock.bottom() ?: return
        // Starting the bottom screen takes input focus; hand it back to the top.
        if (ensure(this, bottom.displayId)) getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
    }

    // ── layouts ──────────────────────────────────────────────────────────

    /** Top screen: the selected console's games. */
    private fun topLayout() = column(
        row(clock(22f), spacer(16), shelfTitleView(), spacer(), StatusView(this, multiline = false, brief = true)),
        titleView(),
        weighted(browser()),
    )

    /** Bottom screen: the consoles. */
    private fun bottomLayout() = column(
        row(clock(28f), spacer(12), clock(16f, "EEE d MMM", Ui.DIM)),
        StatusView(this, multiline = false).apply { textSize = 13f },
        weighted(shelfGridView(columns = GridView.AUTO_FIT)),
        actions(),
    )

    /** External display: consoles on the left, games on the right. */
    private fun dockLayout() = column(
        row(clock(34f), spacer(16), clock(18f, "EEEE d MMMM", Ui.DIM), spacer(24), shelfTitleView()),
        StatusView(this, multiline = false).apply { textSize = 15f },
        weighted(row(
            shelfGridView(columns = 1).apply { layoutParams = LinearLayout.LayoutParams(dp(280), MATCH_PARENT) },
            spacer(16),
            column(titleView(), weighted(browser())).apply {
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            },
        ).apply { gravity = Gravity.TOP }),
        actions(),
    )

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        views.forEach { addView(it, it.layoutParams ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, it.layoutParams ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
    }

    private fun spacer(widthDp: Int? = null) = View(this).apply {
        layoutParams = if (widthDp == null) LinearLayout.LayoutParams(0, 1, 1f) else LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun weighted(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) }

    private fun titleView() = LogoLabel(this, 26f, Ui.TEXT, bold = true, logoHeightPx = dp(44)).apply {
        setPadding(0, dp(8), 0, dp(8))
        title = this
    }

    private fun shelfTitleView() = LogoLabel(this, 18f, Ui.DIM, bold = false, logoHeightPx = dp(26)).apply {
        shelfTitle = this
    }

    /** Games on a shelf, or the Android apps grid. */
    private fun browser() = FrameLayout(this).apply {
        addView(gridView())
        addView(ShelfView(this@ScreenActivity).apply {
            onSelect = { title?.showFile(it.label, Art.gameLogo(this@ScreenActivity, it)) }
            onActivate = ::insertGame
            onMenu = { tileMenu(this, it) }
            shelf = this
        })
    }

    /** Pull the disc out and put it in the console, then start the game. */
    private fun insertGame(game: Library.Game) {
        if (insert.running) return
        if (Library.chosen(this, game) == null) return Library.launch(this, game, displayId) // explains what's missing
        val bottom = Dock.bottom()?.takeIf { role == Role.TOP && !Dock.docked }?.let { open[it.displayId] }
        val at = IntArray(2).also(insert::getLocationInWindow)
        val from = shelf!!.coverRectInWindow().apply { offset(-at[0].toFloat(), -at[1].toFloat()) }
        insert.play(game, if (bottom != null) InsertView.Mode.TOP else InsertView.Mode.SINGLE, from, mediaOnly = Library.mediaView)
        bottom?.insert?.play(game, InsertView.Mode.BOTTOM, null)
        App.main.postDelayed({ Library.launch(this, game, displayId) }, InsertView.TOTAL)
    }

    private fun gridView() = GridView(this).apply {
        adapter = tiles
        numColumns = GridView.AUTO_FIT
        columnWidth = dp(112)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        verticalSpacing = dp(8)
        setSelector(rounded(0x337AA2FF, Ui.ACCENT))
        setOnItemClickListener { _, _, position, _ -> launch(tiles.getItem(position)) }
        setOnItemLongClickListener { _, view, position, _ ->
            tileMenu(view, tiles.getItem(position))
            true
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                title?.show(tiles.getItem(position).label, null)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        grid = this
    }

    private fun shelfGridView(columns: Int) = GridView(this).apply {
        adapter = shelves
        numColumns = columns
        columnWidth = dp(170)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        verticalSpacing = dp(10)
        horizontalSpacing = dp(10)
        setPadding(0, dp(8), 0, dp(8))
        clipToPadding = false
        setSelector(rounded(0x337AA2FF, Ui.ACCENT))
        isDrawSelectorOnTop = true
        setOnItemClickListener { _, _, position, _ -> Library.selected = shelves.getItem(position).id }
        setOnItemLongClickListener { _, view, position, _ ->
            shelfMenu(view, shelves.getItem(position))
            true
        }
        shelfGrid = this
    }

    private fun actions() = row(
        button("Bifrost settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
        button("Android settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) },
    )

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener(onClick)
    }

    // ── opening things, menus ────────────────────────────────────────────

    private fun launch(tile: Tile) = when (tile) {
        is Library.Game -> insertGame(tile)
        is Apps.Entry -> Apps.launch(this, tile, displayId)
        else -> Unit
    }

    private fun PopupMenu.item(text: String, action: () -> Unit) {
        menu.add(text).setOnMenuItemClickListener { action(); true }
    }

    private fun tileMenu(anchor: View, tile: Tile) = PopupMenu(this, anchor).apply {
        when (tile) {
            is Library.Game -> {
                val art = Art.game(this@ScreenActivity, tile)
                item("Launch with…") { chooseEmulator(tile.system, tile) }
                item(if (Library.mediaView) "Show cases" else "Show discs & cartridges") { Library.mediaView = !Library.mediaView }
                item("Set artwork…") { pickArt(art) }
                item("Download artwork") { scrape(tile) }
                if (art.exists()) item("Remove artwork") { art.delete(); Art.changed() }
            }
            is Apps.Entry -> {
                val bottom = Dock.bottom()?.takeIf { role == Role.TOP && !Dock.docked }
                if (bottom != null) item("Open on bottom screen") { Apps.launch(this@ScreenActivity, tile, bottom.displayId) }
                item("App info") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", tile.component.packageName, null)))
                }
            }
        }
        show()
    }

    private fun shelfMenu(anchor: View, shelf: Library.Shelf) {
        if (shelf.id == Library.APPS) return
        val art = Art.system(this, shelf.id)
        PopupMenu(this, anchor).apply {
            item("Default emulator…") { chooseEmulator(shelf.id, null) }
            item("Set artwork…") { pickArt(art) }
            if (art.exists()) item("Remove artwork") { art.delete(); Art.changed() }
            show()
        }
    }

    /** For a game: launch with the pick and remember it. For a system: set its default. */
    private fun chooseEmulator(system: String, game: Library.Game?) {
        val options = Library.available(this, system, game).map { it.first.label }
        if (options.isEmpty()) {
            Toast.makeText(this, "No emulator for ${Library.title(system)} is installed", Toast.LENGTH_LONG).show()
            return
        }
        val current = if (game != null) Library.chosen(this, game)?.first?.label else Library.systemChoice(system)
        AlertDialog.Builder(this)
            .setTitle(game?.label ?: "Default for ${Library.title(system)}")
            .setSingleChoiceItems(options.toTypedArray(), options.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                if (game != null) {
                    Library.setGameChoice(game, options[which])
                    Library.launch(this, game, displayId)
                } else {
                    Library.setSystemChoice(system, options[which])
                }
            }
            .apply {
                if (game != null && Library.gameChoice(game) != null) {
                    setNeutralButton("Use system default") { _, _ -> Library.setGameChoice(game, null) }
                }
            }
            .show()
    }

    private fun pickArt(target: File) {
        pendingArt = target
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQUEST_ART)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = pendingArt ?: return
        val uri = data?.data ?: return
        if (requestCode != REQUEST_ART || resultCode != RESULT_OK) return
        val app = applicationContext
        Thread {
            val ok = Art.save(app, uri, target)
            App.main.post { if (ok) Art.changed() else Toast.makeText(app, "Couldn't read that image", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun scrape(game: Library.Game) {
        val app = applicationContext
        Thread {
            val ok = Scraper.scrape(app, game)
            App.main.post {
                if (ok) Art.changed()
                else Toast.makeText(app, "No artwork found for ${game.label}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ── controller: A opens, X/Y menu, L1/R1 console, Select cases/discs, Start settings ─

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key !in setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_UP) return true
        when (key) {
            KeyEvent.KEYCODE_BUTTON_L1 -> Library.cycle(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> Library.cycle(1)
            KeyEvent.KEYCODE_BUTTON_START -> startActivity(Intent(this, SettingsActivity::class.java))
            KeyEvent.KEYCODE_BUTTON_SELECT -> Library.mediaView = !Library.mediaView
            else -> {
                val focus = currentFocus
                val s = focus as? ShelfView
                if (s != null) {
                    if (key == KeyEvent.KEYCODE_BUTTON_A) s.activate() else s.current?.let { tileMenu(s, it) }
                    return true
                }
                val list = focus as? GridView
                val view = list?.selectedView
                val position = list?.selectedItemPosition ?: -1
                when {
                    view == null || position < 0 -> if (key == KeyEvent.KEYCODE_BUTTON_A) focus?.performClick()
                    key == KeyEvent.KEYCODE_BUTTON_A -> list.performItemClick(view, position, position.toLong())
                    list === grid -> tileMenu(view, tiles.getItem(position))
                    else -> shelfMenu(view, shelves.getItem(position))
                }
            }
        }
        return true
    }

    companion object {
        private const val REQUEST_ART = 1

        /** Live bottom/dock screens by display id ([HomeActivity] is not listed). */
        private val open = HashMap<Int, ScreenActivity>()
        private val starting = HashMap<Int, Long>()

        /** Starts the screen for [displayId] unless one is up or starting. True if it started one. */
        fun ensure(ctx: Context, displayId: Int): Boolean {
            if (displayId in open) return false
            val now = SystemClock.uptimeMillis()
            if (now - (starting[displayId] ?: Long.MIN_VALUE / 2) < 3_000) return false
            starting[displayId] = now
            val intent = Intent(ctx, ScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            return runCatching {
                ctx.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            }.isSuccess
        }

        /** Brings the screen on [displayId] to the front, starting it if needed. */
        fun show(ctx: Context, displayId: Int) {
            val screen = open[displayId] ?: run {
                ensure(ctx, displayId)
                return
            }
            ctx.getSystemService(ActivityManager::class.java).moveTaskToFront(screen.taskId, 0)
        }
    }
}

class HomeActivity : ScreenActivity() {
    override fun onResume() {
        super.onResume()
        goHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goHome()
    }
}
