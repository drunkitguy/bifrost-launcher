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

    @Test
    fun dolphinAzaharEdenAndEdenNightlyLaunch() {
        val rules = File("src/main/assets/es_find_rules.xml").inputStream().use(::parseFindRules)
        val all = vars(installed = setOf("DOLPHIN", "AZAHAR", "EDEN", "EDEN-NIGHTLY"))
        for ((system, label, emulator) in listOf(
            Triple("gc", "Dolphin (Standalone)", "DOLPHIN"),
            Triple("wii", "Dolphin (Standalone)", "DOLPHIN"),
            Triple("n3ds", "Azahar (Standalone)", "AZAHAR"),
            Triple("switch", "Eden (Standalone)", "EDEN"),
            Triple("switch", "Eden Nightly (Standalone)", "EDEN-NIGHTLY"),
        )) {
            val plan = Launch.plan(command(system, label), all)!!
            assertEquals(emulator, plan.emulator)
            assertTrue("$label hands over the ROM", plan.grantsUri)
            assertTrue(rules[emulator]!!.isNotEmpty())
        }
        val eden = Launch.plan(command("switch", "Eden (Standalone)"), all)!!
        assertEquals("android.nfc.action.TECH_DISCOVERED", eden.action)
        assertEquals(all.uri, eden.data)
        assertTrue(rules["EDEN"]!!.any { it.startsWith("dev.eden.eden_emulator/") })
        assertTrue(rules["EDEN-NIGHTLY"]!!.any { it.startsWith("dev.eden.eden_emulator.nightly/") })
        assertTrue(rules["AZAHAR"]!!.any { it.startsWith("org.azahar_emu.azahar/") })
        // Only the nightly installed: still a Switch launcher.
        assertNull(Launch.plan(command("switch", "Eden (Standalone)"), vars(installed = setOf("EDEN-NIGHTLY"))))
        assertTrue(Launch.plan(command("switch", "Eden Nightly (Standalone)"), vars(installed = setOf("EDEN-NIGHTLY"))) != null)
    }

    @Test
    fun logosTurnBlackLetteringWhite() {
        assertEquals(0xFFF2F2F2.toInt(), lightenDark(0xFF000000.toInt()))
        assertEquals(0x80F2F2F2.toInt(), lightenDark(0x80101010.toInt()))
        assertEquals(0xFFC8102E.toInt(), lightenDark(0xFFC8102E.toInt())) // red stays red
        assertEquals(0, lightenDark(0))
    }

    @Test
    fun consoleIsCutAtTheGapBeforeTheDisc() {
        // console 0..59, gap 60..69, disc 70..89, margin 90..99
        val opaque = BooleanArray(100) { it < 60 || it in 70..89 }
        assertEquals(60, consoleEnd(opaque))
        assertNull(consoleEnd(BooleanArray(100) { true }))
    }

    @Test
    fun playTimeAndLastPlayedRead() {
        assertEquals("Under a minute", playTimeText(30_000))
        assertEquals("45m", playTimeText(45 * 60_000L))
        assertEquals("3h 20m", playTimeText((3 * 60 + 20) * 60_000L))
        val day = 86_400_000L
        assertEquals("Today", agoText(10 * day, 10 * day + 5_000))
        assertEquals("Yesterday", agoText(10 * day, 11 * day + 5_000))
        assertEquals("3 days ago", agoText(10 * day, 13 * day))
        assertEquals(0L, sessionLength(100, 50)) // clock went backwards
        assertEquals(12 * 3_600_000L, sessionLength(0, 48 * 3_600_000L)) // left running overnight
    }

    @Test
    fun themeColorsParse() {
        assertEquals(0xFF101418.toInt(), parseHex("#101418"))
        assertEquals(0x80FFFFFF.toInt(), parseHex("80ffffff"))
        assertNull(parseHex("#12345"))
        assertNull(parseHex("blue"))
    }

    @Test
    fun wikipediaMatchingAndYears() {
        val titles = listOf("Mario Kart Wii", "Super Mario Galaxy", "Super Mario Galaxy 2", "List of Wii games")
        assertEquals("Super Mario Galaxy", pickTitle("Super Mario Galaxy (USA)", titles))
        assertEquals("Super Mario Galaxy 2", pickTitle("Super Mario Galaxy 2", titles))
        assertEquals("Metroid Prime (video game)", pickTitle("Metroid Prime", listOf("Metroid Prime (video game)", "Metroid")))
        assertNull(pickTitle("Zelda", titles))
        assertEquals("2007", yearIn("2007 video game"))
        assertEquals("1998", yearIn("Sonic Adventure is a 1998 platform game"))
        assertNull(yearIn("platform game"))
    }

    @Test
    fun consoleYearFromThemeInfo() {
        assertEquals("2001", releaseYear("<text>Manufacturer:  Nintendo</text>\n<text>Released:  2001</text>"))
        assertNull(releaseYear("<text>Manufacturer:  Nintendo</text>"))
    }
}
