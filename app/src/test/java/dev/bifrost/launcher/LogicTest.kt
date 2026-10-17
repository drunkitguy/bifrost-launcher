package dev.bifrost.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun externalDisplayDetection() {
        assertTrue(isExternalType(2, false)) // HDMI / USB-C
        assertTrue(isExternalType(3, false)) // wireless
        assertFalse(isExternalType(1, true)) // built-in panel, even with product info
        assertFalse(isExternalType(5, true)) // virtual display (screen recorder, overlay)
        assertTrue(isExternalType(-1, true)) // type unreadable: EDID decides
        assertFalse(isExternalType(-1, false))
    }

    @Test
    fun panelCommandQuotesApkPath() {
        assertEquals(
            "CLASSPATH='/data/app/a'\\''b/base.apk' app_process /system/bin dev.bifrost.launcher.PanelPower 0 0 3" +
                " 2>&1 | grep -m1 -E '^(OK|ERR)'",
            Root.panelCommand("/data/app/a'b/base.apk", false, listOf(0, 3)),
        )
    }
}
