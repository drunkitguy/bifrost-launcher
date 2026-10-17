package dev.bifrost.launcher

import android.os.IBinder

/**
 * Runs as root through app_process (see [Root.panelCommand]): sets a built-in panel's power mode
 * (0 off, 2 on) behind Android's back. Prints one line, "OK" or "ERR <reason>".
 * Token lookup follows scrcpy: SurfaceControl up to Android 13, DisplayControl (services.jar) after.
 */
object PanelPower {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val mode = args[0].toInt()
            args.drop(1).forEach { setMode(it.toInt(), mode) }
            println("OK")
        } catch (t: Throwable) {
            println("ERR $t")
        }
    }

    private fun setMode(displayId: Int, mode: Int) {
        val dm = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("display"))!!
        val info = dm.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(dm, displayId)
            ?: error("no display $displayId")
        val address = info.javaClass.getField("address").get(info) ?: error("display $displayId has no address")
        val physicalId = address.javaClass.getMethod("getPhysicalDisplayId").invoke(address) as Long
        Class.forName("android.view.SurfaceControl")
            .getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.java)
            .invoke(null, token(physicalId), mode)
    }

    private fun service(name: String) =
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder

    private fun token(physicalId: Long): IBinder {
        runCatching {
            return Class.forName("android.view.SurfaceControl")
                .getMethod("getPhysicalDisplayToken", Long::class.java)
                .invoke(null, physicalId) as IBinder
        }
        val loader = Class.forName("com.android.internal.os.ClassLoaderFactory")
            .getDeclaredMethod(
                "createClassLoader", String::class.java, String::class.java, String::class.java,
                ClassLoader::class.java, Int::class.java, Boolean::class.java, String::class.java,
            )
            .invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null)
            as ClassLoader
        val displayControl = loader.loadClass("com.android.server.display.DisplayControl")
        Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(Runtime.getRuntime(), displayControl, "android_servers")
        return displayControl.getMethod("getPhysicalDisplayToken", Long::class.java).invoke(null, physicalId) as IBinder
    }
}
