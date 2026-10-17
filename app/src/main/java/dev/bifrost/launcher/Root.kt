package dev.bifrost.launcher

import android.content.Context
import android.os.IBinder
import android.os.Parcel

/** Root shell: AYN's built-in root service first (no setup needed on a stock Thor), then `su`. */
internal object Root {
    fun exec(cmd: String): String? = pservice(cmd) ?: su(cmd)

    /**
     * AYN firmware runs a root daemon behind Settings > "Run script as root": binder service
     * "PServerBinder", transaction 0, data = [command, "0"], reply = stdout bytes (first line only).
     * Reachable while SELinux is permissive, which is the Thor's default.
     */
    private fun pservice(cmd: String): String? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "PServerBinder") as IBinder?
        binder?.let { b ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeStringArray(arrayOf(cmd, "0"))
                if (b.transact(0, data, reply, 0)) reply.createByteArray()?.let { String(it) } else null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun su(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    } catch (e: Exception) {
        null
    }

    fun panelPower(ctx: Context, on: Boolean, displayIds: List<Int>): Boolean =
        exec(panelCommand(ctx.applicationInfo.sourceDir, on, displayIds))?.trim()?.startsWith("OK") == true

    internal fun panelCommand(apk: String, on: Boolean, displayIds: List<Int>) =
        "CLASSPATH=${quote(apk)} app_process /system/bin ${PanelPower::class.java.name} ${if (on) 2 else 0} " +
            displayIds.joinToString(" ") + " 2>&1 | grep -m1 -E '^(OK|ERR)'"

    internal fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
