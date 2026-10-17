package dev.bifrost.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

internal object Apps {
    class Entry(val label: String, val component: ComponentName, val icon: Drawable, val game: Boolean)

    /** Games first, then everything else, alphabetical. Only touched on the main thread. */
    var list: List<Entry> = emptyList()
        private set

    fun reload(context: Context) {
        val ctx = context.applicationContext
        Thread {
            val pm = ctx.packageManager
            val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map {
                    Entry(
                        it.loadLabel(pm).toString(),
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        it.loadIcon(pm),
                        it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME,
                    )
                }
                .sortedWith(compareBy({ !it.game }, { it.label.lowercase() }))
            App.main.post {
                list = found
                App.changed()
            }
        }.start()
    }

    fun launch(ctx: Context, app: Entry, displayId: Int) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            ctx.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } catch (e: SecurityException) {
            runCatching { ctx.startActivity(intent) } // display refused: let Android pick
        } catch (e: Exception) {
            // uninstalled since the list loaded; the package receiver reloads it
        }
    }
}
