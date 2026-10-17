package dev.bifrost.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfMathTest {
    private fun project(theta: Float, z: Float = 50f) =
        Box.project(w = 100f, h = 140f, d = 10f, x = 0f, y = 0f, z = z, thetaDeg = theta, focal = 1000f, cx = 500f, cy = 300f)

    @Test
    fun onTheShelfOnlyTheSpineShows() {
        val f = project(90f)
        assertNull(f.cover)
        val spine = f.spine!!
        // Spine faces the viewer, unmirrored: left edge left of right edge, about d wide.
        assertTrue(spine[0] < spine[2])
        assertEquals(10f, spine[2] - spine[0], 0.5f)
        assertEquals(140f, spine[7] - spine[1], 1f)
    }

    @Test
    fun facingFrontOnlyTheCoverShows() {
        val f = project(0f, z = 0f)
        assertNull(f.spine)
        val cover = f.cover!!
        assertTrue(cover[0] < cover[2] && cover[1] < cover[7])
    }

    @Test
    fun pulledOutShowsSpineOnTheLeftOfTheCover() {
        val f = project(24f, z = -30f)
        val cover = assertNotNull(f.cover).let { f.cover!! }
        val spine = f.spine!!
        // The spine's front edge meets the cover's left edge.
        assertEquals(cover[0], spine[2], 0.01f)
        assertTrue(spine[0] < cover[0])
        // Nearer edge of the turned cover looks taller than the far one.
        assertTrue(cover[5] - cover[3] != cover[7] - cover[1])
    }
}
