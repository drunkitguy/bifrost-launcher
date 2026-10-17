package dev.bifrost.launcher

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import java.util.concurrent.Executors

/**
 * Tracks the Thor's displays. Display 0 is the top screen, the bottom screen is the other
 * built-in panel, and anything external (USB-C/HDMI dock, wireless) means "docked".
 */
object Dock : DisplayManager.DisplayListener {
    private const val TYPE_INTERNAL = 1
    private const val HARD_OFF = "hard_off"
    private const val PANELS_OFF = "panels_off"

    private lateinit var app: Context
    private lateinit var dm: DisplayManager
    private val rootThread = Executors.newSingleThreadExecutor()
    private var known: Boolean? = null
    private var topState = Display.STATE_UNKNOWN

    // Display.getType() is hidden but greylisted, and the only reliable built-in vs external signal.
    private val getType = runCatching { Display::class.java.getMethod("getType") }.getOrNull()

    val docked get() = known == true

    /** Power the built-in panels off (root) instead of only showing black on them. */
    var hardOff: Boolean
        get() = App.prefs.getBoolean(HARD_OFF, true)
        set(value) {
            App.prefs.edit().putBoolean(HARD_OFF, value).apply()
            if (docked) setPanels(on = !value)
        }

    fun init(context: Context) {
        app = context.applicationContext
        dm = app.getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(this, App.main)
        update()
    }

    fun external(): Display? = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && isExternal(it) }

    fun bottom(): Display? = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && type(it) == TYPE_INTERNAL }

    fun isExternal(d: Display) = isExternalType(type(d), d.deviceProductInfo != null)

    private fun type(d: Display): Int = getType?.let { runCatching { it.invoke(d) as Int }.getOrNull() } ?: -1

    override fun onDisplayAdded(displayId: Int) = update()
    override fun onDisplayRemoved(displayId: Int) = update()
    override fun onDisplayChanged(displayId: Int) = update()

    private fun update() {
        val ext = external()
        val was = known
        known = ext != null
        val state = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
        val woke = state == Display.STATE_ON && topState != Display.STATE_ON
        topState = state
        // Android re-powers the panels on every wake, so the hard-off is re-applied then too.
        // was == null (process start) restores panels a dead process may have left off.
        if (was != known || (docked && woke)) setPanels(on = !docked)
        if (ext != null) ScreenActivity.ensure(app, ext.displayId)
        App.changed()
    }

    private fun setPanels(on: Boolean) {
        val prefs = App.prefs
        if (on && !prefs.getBoolean(PANELS_OFF, false)) return
        if (!on && !hardOff) return
        val ids = listOfNotNull(Display.DEFAULT_DISPLAY, bottom()?.displayId)
        rootThread.execute {
            if (Root.panelPower(app, on, ids)) prefs.edit().putBoolean(PANELS_OFF, !on).apply()
        }
    }
}

/** Display.TYPE_EXTERNAL = 2, TYPE_WIFI = 3. If the type is unreadable, an EDID means a real monitor. */
internal fun isExternalType(type: Int, hasProductInfo: Boolean) = type == 2 || type == 3 || (type < 0 && hasProductInfo)
