package dev.bifrost.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
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

/**
 * One class for every screen; the layout follows the display it lands on:
 * TOP (display 0, via [HomeActivity]): app grid. BOTTOM (built-in second panel): dashboard.
 * DOCK (external display): grid and dashboard together, while both built-in screens go dark.
 */
open class ScreenActivity : Activity() {
    private enum class Role { TOP, BOTTOM, DOCK }

    private lateinit var role: Role
    private lateinit var cover: View
    private var displayId = Display.INVALID_DISPLAY
    private var grid: GridView? = null
    private var title: TextView? = null
    private var dark: Boolean? = null
    private val tiles = AppAdapter(this)
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
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(content)
            addView(cover)
        })
        grid?.requestFocus()
        App.listeners += listener
        refresh()
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
        tiles.apps = Apps.list
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

    private fun topLayout() = column(
        row(clock(22f), spacer(), StatusView(this, multiline = false, brief = true)),
        titleView(),
        weighted(gridView()),
    )

    private fun bottomLayout() = column(
        clock(64f).apply { gravity = Gravity.CENTER },
        clock(18f, "EEEE d MMMM", Ui.DIM).apply { gravity = Gravity.CENTER },
        StatusView(this, multiline = true).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        },
        weighted(View(this)),
        actions(),
    )

    private fun dockLayout() = column(
        row(clock(34f), spacer(16), clock(18f, "EEEE d MMMM", Ui.DIM)),
        StatusView(this, multiline = false).apply { textSize = 15f },
        titleView(),
        weighted(gridView()),
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

    private fun titleView() = label(26f, bold = true).apply {
        setPadding(0, dp(8), 0, dp(8))
        maxLines = 1
        title = this
    }

    private fun gridView() = GridView(this).apply {
        adapter = tiles
        numColumns = GridView.AUTO_FIT
        columnWidth = dp(112)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        verticalSpacing = dp(8)
        setSelector(rounded(0x337AA2FF, Ui.ACCENT))
        setOnItemClickListener { _, _, position, _ -> Apps.launch(this@ScreenActivity, tiles.getItem(position), displayId) }
        setOnItemLongClickListener { _, view, position, _ ->
            menu(view, tiles.getItem(position))
            true
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                title?.text = tiles.getItem(position).label
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                title?.text = ""
            }
        }
        grid = this
    }

    private fun actions() = row(
        button("Settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) },
        button("Home app") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
        button(hardOffLabel()) {
            Dock.hardOff = !Dock.hardOff
            (it as Button).text = hardOffLabel()
        },
    )

    private fun hardOffLabel() = "Docked: built-in screens " + if (Dock.hardOff) "powered off" else "black"

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener(onClick)
    }

    private fun menu(anchor: View, app: Apps.Entry) {
        val bottom = Dock.bottom()?.takeIf { role == Role.TOP && !Dock.docked }
        PopupMenu(this, anchor).apply {
            if (bottom != null) {
                menu.add("Open on bottom screen").setOnMenuItemClickListener {
                    Apps.launch(this@ScreenActivity, app, bottom.displayId)
                    true
                }
            }
            menu.add("App info").setOnMenuItemClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.component.packageName, null)))
                true
            }
            show()
        }
    }

    // ── controller: A opens, X/Y opens the app menu ─────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val open = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> true
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> false
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_UP) return true
        val g = grid
        val focus = currentFocus
        if (g != null && focus === g) {
            val view = g.selectedView ?: return true
            val position = g.selectedItemPosition
            if (open) g.performItemClick(view, position, position.toLong()) else menu(view, tiles.getItem(position))
        } else if (open) {
            focus?.performClick()
        }
        return true
    }

    companion object {
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
