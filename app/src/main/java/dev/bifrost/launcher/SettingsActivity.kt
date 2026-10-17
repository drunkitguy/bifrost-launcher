package dev.bifrost.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var folder: TextView
    private lateinit var art: TextView
    private lateinit var hardOff: Button
    private lateinit var view: Button
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folder = label(15f, Ui.DIM)
        art = label(15f, Ui.DIM)
        view = button("") {
            Library.mediaView = !Library.mediaView
            render()
        }
        hardOff = button("") {
            Dock.hardOff = !Dock.hardOff
            render()
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
            listOf(
                label(28f, bold = true).apply { text = "Bifrost settings" },
                section("Games"),
                folder,
                button("Choose ROMs folder") {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER)
                },
                button("Rescan games") { Library.rescan(this@SettingsActivity) },
                button("Download missing artwork") { scrapeAll() },
                art,
                view,
                label(14f, Ui.DIM).apply { text = HELP },
                section("Docking"),
                hardOff,
                section("System"),
                button("Choose home app") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
                button("Android settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) },
            ).forEach(::addView)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(column)
        })
        App.listeners += listener
        render()
    }

    override fun onDestroy() {
        App.listeners -= listener
        super.onDestroy()
    }

    private fun render() {
        val tree = Library.tree
        val count = Library.games.values.sumOf { it.size }
        folder.text = when {
            tree == null -> "No ROMs folder chosen yet."
            else -> "ROMs folder: " + docIdToPath(DocumentsContract.getTreeDocumentId(tree)) +
                "\n$count games in ${Library.games.size} systems" + if (Library.scanning) " · scanning…" else ""
        }
        view.text = "Game shelf shows: " + if (Library.mediaView) "discs & cartridges" else "cases"
        hardOff.text = "Docked: built-in screens " + if (Dock.hardOff) "powered off (root)" else "black only"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        Library.setTree(this, uri)
    }

    private fun scrapeAll() {
        if (scraping) return
        scraping = true
        art.text = "Looking up artwork…"
        val app = applicationContext
        Thread {
            Scraper.scrapeMissing(app) { done, total, found ->
                App.main.post {
                    art.text = "Artwork: checked $done of $total, found $found"
                    Art.changed()
                }
            }
            App.main.post {
                scraping = false
                if (art.text == "Looking up artwork…") art.text = "All games already have artwork (or none can be scraped)."
            }
        }.start()
    }

    private fun section(text: String) = label(13f, Ui.ACCENT, bold = true).apply {
        this.text = text.uppercase()
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener(onClick)
    }

    companion object {
        private const val REQUEST_FOLDER = 1
        private var scraping = false

        private const val HELP =
            "Put each console's games in a subfolder named like ES-DE (gc, wii, n3ds, switch, ps2, psx, psp, nds, " +
                "n64, dreamcast, gba, snes…) or by full name (\"Nintendo GameCube\"). Long-press a console to pick its " +
                "emulator, a game to pick one just for it. Custom launchers: put bifrost_systems.xml and " +
                "bifrost_find_rules.xml (ES-DE format) in the ROMs folder. Artwork comes from the libretro thumbnail " +
                "server; long-press a game or console to use your own image. System logos and console art: Slate " +
                "theme for ES-DE (CC BY-NC-SA), downloaded on first use; logos are their owners' trademarks."
    }
}
