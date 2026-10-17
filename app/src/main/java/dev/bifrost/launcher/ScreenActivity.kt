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
    private var carousel: HomeCarousel? = null
    private var menu: ConsoleMenu? = null
    /** Bottom screen only: the 3DS/DS icon row, which lives down there like on the real thing. */
    private var bottomMenu: ConsoleMenu? = null
    private var hints: HintBar? = null
    private var details: Details? = null
    private var title: LogoLabel? = null
    private var shelfTitle: LogoLabel? = null
    private var dark: Boolean? = null
    private var artVersion = -1
    private var pendingArt: File? = null
    private val tiles = TileAdapter(this)
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
        if (this is HomeActivity) home = this
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
        insert = InsertView(this)
        setContentView(FrameLayout(this).apply {
            background = Theme.background(this@ScreenActivity)
            addView(content)
            addView(insert)
            addView(cover)
        })
        carousel?.requestFocus()
        App.listeners += listener
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::insert.isInitialized) insert.stop()
        Library.endSession()
    }

    override fun onDestroy() {
        App.listeners -= listener
        if (open[displayId] === this) open.remove(displayId)
        if (home === this) home = null
        super.onDestroy()
    }

    /** Back (or B) goes from a console's shelf up to the home row; a home screen never closes. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (Library.inside) Library.inside = false
    }

    private fun refresh() {
        if (isFinishing) return
        if (role == Role.DOCK && !Dock.docked) {
            finish()
            return
        }
        val inside = Library.inside
        val apps = Library.selected == Library.APPS
        val items = Library.tiles()
        val style = if (inside && !apps) Menus.styleFor(Library.selected) else null
        carousel?.visibility = if (inside) View.GONE else View.VISIBLE
        grid?.visibility = if (inside && apps) View.VISIBLE else View.GONE
        shelf?.visibility = if (inside && !apps && style == null) View.VISIBLE else View.GONE
        menu?.visibility = if (style != null) View.VISIBLE else View.GONE
        title?.visibility = if (inside && style == null) View.VISIBLE else View.GONE
        // 3DS/DS: icons on the bottom screen, banner on top, as on the hardware.
        val split = style == ConsoleMenu.Style.DS && !Dock.docked && Dock.bottom() != null
        menu?.let { m ->
            if (style != null) m.style = style
            m.part = if (split && role == Role.TOP) ConsoleMenu.Part.TOP else ConsoleMenu.Part.BOTH
            m.games = Library.gamesOf(Library.selected)
            m.sync(Library.highlight)
        }
        bottomMenu?.let { m ->
            m.visibility = if (split) View.VISIBLE else View.GONE
            m.style = ConsoleMenu.Style.DS
            m.part = ConsoleMenu.Part.BOTTOM
            if (split) {
                m.games = Library.gamesOf(Library.selected)
                m.sync(Library.highlight)
            }
        }
        carousel?.shelves = Library.shelves()
        if (apps) {
            if (items !== tiles.items) {
                tiles.items = items
                grid?.setSelection(0)
                title?.show(items.firstOrNull()?.label.orEmpty(), null)
            }
        } else {
            shelf?.games = Library.gamesOf(Library.selected)
            if (artVersion != Art.version) shelf?.invalidate()
        }
        if (inside && items.isEmpty()) title?.show(emptyHint(), null)
        // Keep the controller on whichever browser is showing.
        val browsers = listOfNotNull(carousel, grid, shelf, menu)
        val visible = browsers.firstOrNull { it.visibility == View.VISIBLE }
        if (visible != null && browsers.any { it.hasFocus() && it !== visible }) visible.requestFocus()
        artVersion = Art.version
        shelfTitle?.show(Library.title(Library.selected), Logos.get(this, Library.selected, Logos.Kind.LOGO, dp(26), onDark = !Theme.current.light) { refresh() })
        shelf?.media = Library.mediaView
        if (inside && !apps) insert.post { insert.prefetch(Library.selected) }
        hints?.set(hintsFor())
        details?.bind()

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

    private fun hintsFor(): List<Pair<String, String>> = when {
        !Library.inside -> listOf("A" to "Open", "X" to "Options", "L1 R1" to "Console", "STA" to "Settings")
        Library.selected == Library.APPS -> listOf("A" to "Open", "B" to "Home", "X" to "Options")
        else -> listOf("A" to "Play", "B" to "Home", "X" to "Options", "SEL" to if (Library.mediaView) "Cases" else "Discs", "L1 R1" to "Console")
    }

    // ── layouts ──────────────────────────────────────────────────────────

    /** Top screen: the home row of consoles, or a console's shelf; controller hints below. */
    private fun topLayout() = column(
        row(shelfTitleView(), spacer(), StatusBarView(this)),
        titleView(),
        weighted(browser()),
        hintBar(),
    )

    /** Bottom screen: details of whatever is highlighted up top, and the dock. */
    private fun bottomLayout() = column(
        row(clock(20f), spacer(10), clock(14f, "EEE d MMM", Ui.DIM), spacer(), StatusView(this, multiline = false).apply { textSize = 12f }),
        weighted(FrameLayout(this).apply {
            addView(Details(this@ScreenActivity).also { details = it }.view)
            addView(ConsoleMenu(this@ScreenActivity).apply {
                visibility = View.GONE
                onSelect = { Library.highlight = it }
                onActivate = { play(it) }
                onMenu = { tileMenu(this, it) }
                bottomMenu = this
            })
        }),
        dockRow(),
    )

    /** External display: everything on one screen, browser left and details right. */
    private fun dockLayout() = column(
        row(shelfTitleView(), spacer(), StatusView(this, multiline = false).apply { textSize = 14f }, spacer(16), StatusBarView(this)),
        weighted(row(
            column(titleView(), weighted(browser())).apply {
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1.8f)
            },
            spacer(16),
            column(weighted(Details(this).also { details = it }.view), dockRow()).apply {
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            },
        ).apply { gravity = Gravity.TOP }),
        hintBar(),
    )

    private fun hintBar() = HintBar(this).also { hints = it }

    /** Icon dock: home row, Android apps, cases/discs, settings. */
    private fun dockRow() = row(
        glyphButton(Glyph.Kind.HOME, "Home") { Library.inside = false },
        glyphButton(Glyph.Kind.GRID, "Android apps") {
            Library.selected = Library.APPS
            Library.inside = true
        },
        glyphButton(Glyph.Kind.DISC, "Cases or discs") { Library.mediaView = !Library.mediaView },
        glyphButton(Glyph.Kind.GEAR, "Bifrost settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
    ).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, 0)
    }

    private fun glyphButton(kind: Glyph.Kind, description: String, onClick: () -> Unit) = android.widget.ImageButton(this).apply {
        setImageDrawable(Glyph(kind))
        contentDescription = description
        background = rounded(Ui.SURFACE, 0x22FFFFFF)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(48)).apply { marginStart = dp(6); marginEnd = dp(6) }
    }

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
        addView(HomeCarousel(this@ScreenActivity).apply {
            onSelect = { Library.selected = it.id }
            onEnter = {
                Library.selected = it.id
                Library.inside = true
            }
            onMenu = { shelfMenu(this, it) }
            carousel = this
        })
        addView(gridView())
        addView(ConsoleMenu(this@ScreenActivity).apply {
            visibility = View.GONE
            onSelect = { Library.highlight = it }
            onActivate = ::insertGame
            onMenu = { tileMenu(this, it) }
            menu = this
        })
        addView(ShelfView(this@ScreenActivity).apply {
            onSelect = {
                title?.showFile(it.label, Art.gameLogo(this@ScreenActivity, it))
                Library.highlight = it
            }
            onActivate = ::insertGame
            onMenu = { tileMenu(this, it) }
            shelf = this
        })
    }

    /** The screen holding the shelf: the top one, or the TV when docked. */
    private fun browserScreen(): ScreenActivity =
        (if (Dock.docked) Dock.external()?.let { open[it.displayId] } else home) ?: this

    /** Pull the disc out and put it in the console, then start the game. */
    private fun insertGame(game: Library.Game) {
        if (browserScreen() !== this) return browserScreen().insertGame(game)
        if (insert.running) return
        if (Library.chosen(this, game) == null) return Library.launch(this, game, displayId) // explains what's missing
        val bottom = Dock.bottom()?.takeIf { role == Role.TOP && !Dock.docked }?.let { open[it.displayId] }
        val at = IntArray(2).also(insert::getLocationInWindow)
        val from = (menu?.takeIf { it.visibility == View.VISIBLE }?.selectedRectInWindow() ?: shelf!!.coverRectInWindow())
            .apply { offset(-at[0].toFloat(), -at[1].toFloat()) }
        val fromMenu = menu?.visibility == View.VISIBLE
        insert.play(game, if (bottom != null) InsertView.Mode.TOP else InsertView.Mode.SINGLE, from, mediaOnly = Library.mediaView || fromMenu)
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

    // ── opening things, menus ────────────────────────────────────────────

    private fun launch(tile: Tile) = when (tile) {
        is Library.Game -> insertGame(tile)
        is Apps.Entry -> Apps.launch(this, tile, displayId)
        else -> Unit
    }

    private fun PopupMenu.item(text: String, action: () -> Unit) {
        menu.add(text).setOnMenuItemClickListener { action(); true }
    }

    internal fun tileMenu(anchor: View, tile: Tile) = PopupMenu(this, anchor).apply {
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
        if (shelf.id == Library.APPS || shelf.id == Library.RECENT) return
        val art = Art.system(this, shelf.id)
        PopupMenu(this, anchor).apply {
            item("Default emulator…") { chooseEmulator(shelf.id, null) }
            if (Menus.styleFor(shelf.id) != null) item("Show as shelf") { Menus.useShelf(shelf.id, true) }
            else if (App.prefs.getBoolean("shelf.${shelf.id}", false)) item("Show console menu") { Menus.useShelf(shelf.id, false) }
            item("Set artwork…") { pickArt(art) }
            if (art.exists()) item("Remove artwork") { art.delete(); Art.changed() }
            show()
        }
    }

    /** For a game: launch with the pick and remember it. For a system: set its default. */
    internal fun chooseEmulator(system: String, game: Library.Game?) {
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
                (focus as? ConsoleMenu)?.let { m ->
                    if (key == KeyEvent.KEYCODE_BUTTON_A) m.activate() else m.current?.let { tileMenu(m, it) }
                    return true
                }
                (focus as? HomeCarousel)?.let { c ->
                    if (key == KeyEvent.KEYCODE_BUTTON_A) c.enter() else c.current?.let { shelfMenu(c, it) }
                    return true
                }
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
        private var home: ScreenActivity? = null

        /** Rebuilds every screen, e.g. after a theme change. */
        fun recreateAll() {
            home?.recreate()
            open.values.toList().forEach { it.recreate() }
        }

        /** Starts [game] from whichever screen holds the shelf, with the insert animation. */
        internal fun play(game: Library.Game) {
            (if (Dock.docked) Dock.external()?.let { open[it.displayId] } else home)?.insertGame(game)
        }

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
