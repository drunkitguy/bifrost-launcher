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
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import java.io.File

/**
 * One class for every screen, laid out like Cocoon by the display it lands on:
 * TOP (display 0, via [HomeActivity]): the art of what's selected. BOTTOM (the second panel): the
 * consoles and games, driven by the controller. DOCK (external display): one composed screen, the
 * art filling it and the row of consoles or games along the bottom, while the built-in screens go dark.
 */
open class ScreenActivity : Activity() {
    private enum class Role { TOP, BOTTOM, DOCK }

    private lateinit var role: Role
    private lateinit var cover: View
    private var displayId = Display.INVALID_DISPLAY
    private var hero: HeroView? = null
    private var browser: BrowserView? = null
    private var dark: Boolean? = null
    private var pendingArt: File? = null
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
        if (this is HomeActivity) home = this else {
            open[displayId] = this
            starting.remove(displayId)
        }
        cover = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true // swallow touches on a dark screen
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            if (role != Role.BOTTOM) addView(HeroView(this@ScreenActivity).apply {
                tv = role == Role.DOCK
                onStop = ::stopGame
                hero = this
            })
            if (role != Role.TOP) addView(BrowserView(this@ScreenActivity).apply {
                row = role == Role.DOCK
                onOpen = ::open
                onMenu = { item -> if (item is Library.Shelf) shelfMenu(this, item) else tileMenu(this, item) }
                onSettings = { startActivity(Intent(this@ScreenActivity, SettingsActivity::class.java)) }
                onStop = ::stopGame
                browser = this
            }, if (role == Role.DOCK) FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM) else
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(cover)
            // TV: the row takes the bottom 40% of the screen, over the art.
            if (role == Role.DOCK) addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) browser?.let { b ->
                    b.layoutParams = (b.layoutParams as FrameLayout.LayoutParams).apply { height = ((bottom - top) * 0.4f).toInt() }
                    v.post { b.requestLayout() }
                }
            }
        })
        browser?.requestFocus()
        App.listeners += listener
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Library.endSession()
    }

    override fun onDestroy() {
        App.listeners -= listener
        if (open[displayId] === this) open.remove(displayId)
        if (home === this) home = null
        super.onDestroy()
    }

    /** Back goes from a console's games up to the consoles; a home screen never closes. */
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
        browser?.bind()
        hero?.invalidate()

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

    /** Home pressed (or the top screen came back): the controller belongs to the browser screen. */
    protected fun goHome() {
        val ext = Dock.external()
        if (ext != null) {
            show(this, ext.displayId)
            return
        }
        val bottom = Dock.bottom() ?: return
        ensure(this, bottom.displayId)
        show(this, bottom.displayId)
    }

    /** Games run on the top screen, or on the TV when docked. */
    private fun gameDisplay() = Dock.external()?.displayId ?: Display.DEFAULT_DISPLAY

    private fun open(item: Any) {
        when (item) {
            is Library.Shelf -> {
                Library.selected = item.id
                Library.inside = true
            }
            is Library.Game -> Library.launch(this, item, gameDisplay())
            is Apps.Entry -> Apps.launch(this, item, gameDisplay())
        }
    }

    /** The screen showing the art: the top one, or this one when docked. */
    private fun heroScreen(): ScreenActivity? = if (role == Role.TOP || role == Role.DOCK) this else home

    private fun stopGame() {
        val app = applicationContext
        Thread {
            val ok = Library.stopRunning()
            App.main.post {
                if (ok) Library.endSession()
                else Toast.makeText(app, "Closing a game needs root (or AYN's root service)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun PopupMenu.item(text: String, action: () -> Unit) {
        menu.add(text).setOnMenuItemClickListener { action(); true }
    }

    internal fun tileMenu(anchor: View, tile: Any) = PopupMenu(this, anchor).apply {
        when (tile) {
            is Library.Game -> {
                val art = Art.game(this@ScreenActivity, tile)
                item("Launch with…") { chooseEmulator(tile.system, tile) }
                item("Set artwork…") { pickArt(art) }
                item("Download artwork") { scrape(tile) }
                if (art.exists()) item("Remove artwork") { art.delete(); Art.changed() }
            }
            is Apps.Entry -> {
                val bottom = Dock.bottom()?.takeIf { !Dock.docked }
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
                    Library.launch(this, game, gameDisplay())
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

    // ── controller: A open, B back, X options, Y info, L1/R1 console, Select list, Start settings ─

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Handheld: the top screen shows art only, so its keys belong to the bottom screen's browser.
        if (role == Role.TOP && !Dock.docked) Dock.bottom()?.let { open[it.displayId] }?.let { return it.dispatchKeyEvent(event) }
        val key = event.keyCode
        if (key !in setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)) {
            if (browser != null && event.keyCode in DPAD && !browser!!.hasFocus()) browser!!.requestFocus()
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_UP) return true
        val b = browser ?: return true
        val heroView = heroScreen()?.hero
        when (key) {
            KeyEvent.KEYCODE_BUTTON_A -> if (heroView?.showInfo == true) heroView.showInfo = false else b.open()
            KeyEvent.KEYCODE_BUTTON_B -> if (heroView?.showInfo == true) heroView.showInfo = false else if (Library.inside) Library.inside = false
            KeyEvent.KEYCODE_BUTTON_X -> b.current?.let { if (it is Library.Shelf) shelfMenu(b, it) else tileMenu(b, it) }
            KeyEvent.KEYCODE_BUTTON_Y -> heroView?.let { it.showInfo = !it.showInfo }
            KeyEvent.KEYCODE_BUTTON_L1 -> if (Library.inside) Library.cycle(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (Library.inside) Library.cycle(1)
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (Library.inside) Library.listView = !Library.listView
            KeyEvent.KEYCODE_BUTTON_START -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        return true
    }

    companion object {
        private const val REQUEST_ART = 1
        private val DPAD = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)

        /** Live bottom/dock screens by display id ([HomeActivity] is not listed). */
        private val open = HashMap<Int, ScreenActivity>()
        private val starting = HashMap<Int, Long>()
        private var home: ScreenActivity? = null

        /** Rebuilds every screen, e.g. after a theme change. */
        fun recreateAll() {
            home?.recreate()
            open.values.toList().forEach { it.recreate() }
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
