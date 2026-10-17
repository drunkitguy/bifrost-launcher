package dev.bifrost.launcher

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The second screen's info panel, like Konsoru's and Cocoon's: whatever is highlighted on the
 * shelf (or the console on the home row) with its art, logo, play time and a big Play button.
 */
internal class Details(private val screen: ScreenActivity) {
    private val systemLogo = LogoLabel(screen, 16f, Ui.DIM, bold = false, logoHeightPx = screen.dp(28))
    private val hero = ImageView(screen).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val name = LogoLabel(screen, 22f, Ui.TEXT, bold = true, logoHeightPx = screen.dp(52))
    private val pills = LinearLayout(screen).apply { gravity = Gravity.CENTER }
    private val play = Button(screen).apply {
        isAllCaps = false
        textSize = 20f
        setTextColor(0xFF0E1116.toInt())
        background = screen.rounded(Ui.ACCENT, Ui.ACCENT)
        setCompoundDrawablesRelative(Glyph(Glyph.Kind.PLAY, 0xFF0E1116.toInt()).apply { setBounds(0, 0, screen.dp(22), screen.dp(22)) }, null, null, null)
        compoundDrawablePadding = screen.dp(10)
        setPadding(screen.dp(28), 0, screen.dp(28), 0)
    }
    private val options = Button(screen).apply {
        isAllCaps = false
        text = "Options"
        background = screen.rounded(Ui.SURFACE, 0x33FFFFFF)
        setTextColor(Ui.TEXT)
    }
    private var key: String? = null

    val view: View = LinearLayout(screen).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(screen.dp(16), screen.dp(12), screen.dp(16), screen.dp(12))
        background = screen.rounded(0xFF151922.toInt(), 0x22FFFFFF)
        addView(systemLogo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = screen.dp(8) })
        addView(name, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = screen.dp(8) })
        addView(pills, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = screen.dp(8) })
        addView(LinearLayout(screen).apply {
            gravity = Gravity.CENTER
            addView(play, LinearLayout.LayoutParams(WRAP_CONTENT, screen.dp(52)))
            addView(options, LinearLayout.LayoutParams(WRAP_CONTENT, screen.dp(52)).apply { marginStart = screen.dp(10) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = screen.dp(12) })
    }

    fun bind() {
        val game = Library.highlight?.takeIf { Library.inside && Library.selected != Library.APPS && it in Library.gamesOf(Library.selected) }
        if (game == null) return bindShelf(Library.selected)
        val now = "${game.docId}:${Library.inside}:${Art.version}:${Library.mediaView}:${Library.lastPlayed(game)}"
        if (now == key) return
        key = now
        val look = Look.of(game.system)
        systemLogo.show(Library.title(game.system), Logos.get(screen, game.system, Logos.Kind.LOGO, screen.dp(28)) { refresh() })
        hero.setImageBitmap(
            if (Library.mediaView) Faces.media(screen, game, look, screen.dp(260)) { refresh() }
            else Faces.cover(screen, game, look, screen.dp(260)) { refresh() },
        )
        name.showFile(game.label, Art.gameLogo(screen, game))
        val emulator = Library.chosen(screen, game)?.first?.label?.removeSuffix(" (Standalone)")
        val last = Library.lastPlayed(game)
        setPills(
            emulator ?: "No emulator installed",
            if (last > 0) agoText(last, System.currentTimeMillis()) else "Never played",
            Library.playTime(game).takeIf { it > 0 }?.let(::playTimeText),
        )
        play.text = "Play"
        play.setOnClickListener { ScreenActivity.play(game) }
        options.visibility = View.VISIBLE
        options.text = "Options"
        options.setOnClickListener { screen.tileMenu(options, game) }
    }

    /** Nothing highlighted: describe the console (or apps, or recently played). */
    private fun bindShelf(id: String) {
        val now = "$id:${Library.inside}:${Library.gamesOf(id).size}:${Art.version}"
        if (now == key) return
        key = now
        systemLogo.show("", null)
        hero.setImageBitmap(Logos.get(screen, id, Logos.Kind.SCENE, screen.dp(220)) { refresh() })
        name.show(Library.title(id), Logos.get(screen, id, Logos.Kind.LOGO, screen.dp(52)) { refresh() })
        val count = if (id == Library.APPS) "${Apps.list.size} apps" else "${Library.gamesOf(id).size} games"
        val emulator = if (id == Library.APPS || id == Library.RECENT) null else Library.available(screen, id).let { options ->
            (options.firstOrNull { it.first.label == Library.systemChoice(id) } ?: options.firstOrNull { "Standalone" in it.first.label } ?: options.firstOrNull())
                ?.first?.label?.removeSuffix(" (Standalone)") ?: "No emulator installed"
        }
        setPills(count, emulator)
        play.text = if (Library.inside) "Home" else "Open"
        play.setOnClickListener { Library.inside = !Library.inside }
        options.visibility = if (emulator != null) View.VISIBLE else View.GONE
        options.text = "Emulator"
        options.setOnClickListener { screen.chooseEmulator(id, null) }
    }

    private fun setPills(vararg values: String?) {
        pills.removeAllViews()
        values.filterNotNull().forEach {
            pills.addView(screen.pill(it), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = screen.dp(4); marginEnd = screen.dp(4) })
        }
    }

    private fun refresh() {
        key = null
        bind()
    }
}
