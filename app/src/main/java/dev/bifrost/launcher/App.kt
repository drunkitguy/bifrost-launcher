package dev.bifrost.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("bifrost", MODE_PRIVATE)
        Theme.load(this)
        Apps.reload(this)
        Library.init(this)
        Dock.init(this)
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Apps.reload(context)
        }, packages)
    }

    companion object {
        val main = Handler(Looper.getMainLooper())
        lateinit var prefs: SharedPreferences

        /** Every screen re-renders on any change: displays, dock state, installed apps. */
        val listeners = LinkedHashSet<() -> Unit>()
        fun changed() = listeners.toList().forEach { it() }
    }
}
