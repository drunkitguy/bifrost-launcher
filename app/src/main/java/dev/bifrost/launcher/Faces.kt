package dev.bifrost.launcher

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File

/** Decoded and rendered game images, cached; decoding happens off the main thread. */
internal object Faces {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()

    /** [file] decoded at about [size] px; null until ready ([onReady] fires) or if it doesn't exist. */
    private fun decoded(key: String, file: File, size: Int, onReady: () -> Unit): Bitmap? {
        cache.get(key)?.let { return it }
        if (!file.exists() || !pending.add(key)) return null
        Images.pool.execute {
            val bitmap = Images.decode(file, size)
            App.main.post {
                pending.remove(key)
                if (bitmap != null) {
                    cache.put(key, bitmap)
                    onReady()
                }
            }
        }
        return null
    }

    fun gameLogo(ctx: Context, game: Library.Game, h: Int, onReady: () -> Unit) =
        decoded("l:${game.docId}:$h:${Art.version}", Art.gameLogo(ctx, game), h, onReady)

    fun art(ctx: Context, game: Library.Game, size: Int, onReady: () -> Unit) =
        decoded("a:${game.docId}:$size:${Art.version}", Art.game(ctx, game), size, onReady)

    /** Your own picture for a console (long-press it), shown instead of the downloaded scene. */
    fun systemArt(ctx: Context, system: String, size: Int, onReady: () -> Unit) =
        decoded("s:$system:$size:${Art.version}", Art.system(ctx, system), size, onReady)

    /** Top-screen art: fan art, else the title screen, else box art. [titled] = it already shows the logo. */
    class Hero(val bitmap: Bitmap, val titled: Boolean)

    fun hero(ctx: Context, game: Library.Game, size: Int, onReady: () -> Unit): Hero? {
        decoded("h:${game.docId}:$size:${Art.version}", Art.hero(ctx, game), size, onReady)?.let { return Hero(it, false) }
        if (Art.hero(ctx, game).exists()) return null // still decoding the best one
        decoded("t:${game.docId}:$size:${Art.version}", Art.title(ctx, game), size, onReady)?.let { return Hero(it, true) }
        if (Art.title(ctx, game).exists()) return null
        return art(ctx, game, size, onReady)?.let { Hero(it, false) }
    }

    /** The disc or cartridge with the box art as its label and the system logo on its band. */
    fun media(ctx: Context, game: Library.Game, size: Int, onReady: () -> Unit): Bitmap {
        val look = Look.of(game.system)
        val darkBand = look.media == Look.Media.DISC || look.media == Look.Media.MINI_DISC
        val logo = Logos.get(ctx, game.system, Logos.Kind.LOGO, size / 6, onDark = darkBand && !Render.isLight(look.mediaColor), onReady = onReady)
        val cover = art(ctx, game, size, onReady)
        val key = "m:${game.docId}:$size:${Art.version}:${cover != null}:${logo != null}"
        return cache.get(key) ?: Render.media(look, cover, game.label, logo, size).also { cache.put(key, it) }
    }
}
