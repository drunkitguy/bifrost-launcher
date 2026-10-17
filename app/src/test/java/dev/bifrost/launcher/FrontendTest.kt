package dev.bifrost.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrontendTest {
    private val systems = File("src/main/assets/es_systems.xml").inputStream().use(::parseSystems).associateBy { it.name }

    private fun vars(installed: Set<String> = setOf("DOLPHIN", "NETHERSX2", "VITA3K", "RETROARCH")) = Launch.Vars(
        path = "/storage/emulated/0/ROMs/gc/Super Mario Sunshine (USA).rvz",
        uri = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fgc%2Fx.rvz",
        baseName = "Super Mario Sunshine (USA)",
        romRoot = "/storage/emulated/0/ROMs",
        pkg = { if (it in installed) "pkg.${it.lowercase()}" else null },
        inject = { "PCSE00000\n" },
    )

    private fun command(system: String, label: String) = systems[system]!!.commands.first { it.label == label }.raw

    @Test
    fun bundledSystemsParse() {
        assertTrue(systems.size > 100)
        assertEquals("Nintendo GameCube", systems["gc"]!!.fullname)
        assertTrue("rvz" in systems["gc"]!!.extensions)
        val rules = File("src/main/assets/es_find_rules.xml").inputStream().use(::parseFindRules)
        assertTrue(rules["DOLPHIN"]!!.any { it.startsWith("org.dolphinemu.dolphinemu/") })
    }

    @Test
    fun dolphinPlan() {
        val plan = Launch.plan(command("gc", "Dolphin (Standalone)"), vars())!!
        assertEquals("DOLPHIN", plan.emulator)
        assertEquals("android.intent.action.MAIN", plan.action)
        assertEquals(listOf("android.intent.category.LEANBACK_LAUNCHER"), plan.categories)
        assertEquals("AutoStartFile" to vars().uri, plan.extras.single())
        assertTrue(plan.grantsUri)
    }

    @Test
    fun retroArchPlanUsesPathsAndPackage() {
        val plan = Launch.plan(command("gc", "Dolphin"), vars())!!
        val extras = plan.extras.toMap()
        assertEquals("/storage/emulated/0/ROMs/gc/Super Mario Sunshine (USA).rvz", extras["ROM"])
        assertEquals("/data/data/pkg.retroarch/cores/dolphin_libretro_android.so", extras["LIBRETRO"])
        assertEquals(false, plan.grantsUri)
    }

    @Test
    fun boolIntArrayAndInjectedExtras() {
        val duck = Launch.plan(command("ps2", "NetherSX2 (Standalone)"), vars())!!
        assertTrue(duck.clearTask && duck.clearTop)
        val vita = Launch.plan(command("psvita", "Vita3K (Standalone)"), vars())!!
        assertArrayEquals(arrayOf("-r", "PCSE00000"), vita.extras.single().second as Array<*>)
    }

    @Test
    fun missingEmulatorOrUnsupportedCommandGivesNoPlan() {
        assertNull(Launch.plan(command("gc", "Dolphin (Standalone)"), vars(installed = emptySet())))
        assertNull(Launch.plan("%EMULATOR_DOLPHIN% %ANDROIDAPP%=x", vars()))
    }

    @Test
    fun quotedValuesKeepSpaces() {
        val tokens = Launch.tokens("%EMULATOR_MAME% %EXTRA_cli_params%=\"-rompath 'a;b' -cart '%ROMRAW%'\" %DATA%=adam")
        assertEquals("-rompath 'a;b' -cart '%ROMRAW%'", tokens[1].second)
        assertEquals("%DATA%" to "adam", tokens[2])
    }

    @Test
    fun pathsAndNames() {
        assertEquals("/storage/emulated/0/ROMs/gc/x.rvz", docIdToPath("primary:ROMs/gc/x.rvz"))
        assertEquals("/storage/1234-ABCD/ROMs", docIdToPath("1234-ABCD:ROMs"))
        assertEquals("/storage/emulated/0", docIdToPath("primary:"))
        assertEquals("Super Mario Sunshine", cleanName("Super Mario Sunshine (USA) [!]"))
        assertEquals("(Untitled)", cleanName("(Untitled)"))
    }

    @Test
    fun multiFileGamesShowOnce() {
        val exts = setOf("bin", "cue", "chd", "m3u", "iso")
        assertEquals(listOf("Crash.cue", "Other.iso"),
            visibleGames(listOf("Crash.cue", "Crash (Track 1).bin", "Crash (Track 2).bin", "Other.iso", "readme.txt"), exts))
        assertEquals(listOf("FF7.m3u"), visibleGames(listOf("FF7.m3u", "FF7 (Disc 1).chd", "FF7 (Disc 2).chd"), exts))
    }

    @Test
    fun scraperMatchesLibretroNames() {
        val index = parseIndex(
            """<a href="../">../</a><a href="Super%20Mario%20Sunshine%20%28Europe%29.png">x</a>
               <a href="Super%20Mario%20Sunshine%20%28USA%29.png">x</a><a href="Mario%20_%20Luigi%20%28USA%29.png">x</a>""",
        )
        assertEquals("Super Mario Sunshine (USA).png", index[1].first)
        assertEquals("Super%20Mario%20Sunshine%20%28USA%29.png", bestMatch("Super Mario Sunshine (Japan)", index))
        assertEquals("Super%20Mario%20Sunshine%20%28Europe%29.png", bestMatch("Super Mario Sunshine (Europe)", index))
        assertEquals("Mario%20_%20Luigi%20%28USA%29.png", bestMatch("Mario & Luigi", index))
        assertNull(bestMatch("Zelda", index))
    }
}
