package nz.personal.checkpointwatch.ui

import nz.personal.checkpointwatch.ui.settings.WatchedSuburbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suburbs arrive from the parser upper-case ("HENDERSON"), but a suburb saved by an older version,
 * or matched by a differently-cased list, must still untick. A toggle that can add but not remove
 * is the worst kind of setting.
 */
class WatchedSuburbsTest {

    @Test
    fun `an unwatched suburb is added`() {
        assertEquals(setOf("HENDERSON"), WatchedSuburbs.toggle(emptySet(), "HENDERSON"))
    }

    @Test
    fun `a watched suburb is removed`() {
        assertEquals(emptySet<String>(), WatchedSuburbs.toggle(setOf("HENDERSON"), "HENDERSON"))
    }

    @Test
    fun `removal is case-insensitive, so a differently-cased stored value still unticks`() {
        assertEquals(emptySet<String>(), WatchedSuburbs.toggle(setOf("Henderson"), "HENDERSON"))
        assertEquals(emptySet<String>(), WatchedSuburbs.toggle(setOf("HENDERSON"), "henderson"))
    }

    @Test
    fun `duplicates that differ only in case all go on one tap`() {
        assertEquals(
            emptySet<String>(),
            WatchedSuburbs.toggle(setOf("HENDERSON", "Henderson", "henderson"), "HENDERSON"),
        )
    }

    @Test
    fun `what is stored is upper-case, whatever was tapped`() {
        assertEquals(setOf("PAKURANGA"), WatchedSuburbs.toggle(emptySet(), "pakuranga"))
        assertEquals(setOf("PAKURANGA"), WatchedSuburbs.toggle(emptySet(), "  Pakuranga  "))
    }

    @Test
    fun `the other suburbs are left alone, in order`() {
        val current = linkedSetOf("AVONDALE", "HENDERSON", "PAKURANGA")

        assertEquals(
            listOf("AVONDALE", "PAKURANGA"),
            WatchedSuburbs.toggle(current, "HENDERSON").toList(),
        )
        assertEquals(
            listOf("AVONDALE", "HENDERSON", "PAKURANGA", "SILVERDALE"),
            WatchedSuburbs.toggle(current, "SILVERDALE").toList(),
        )
    }

    @Test
    fun `a blank name changes nothing`() {
        val current = setOf("HENDERSON")
        assertEquals(current, WatchedSuburbs.toggle(current, "   "))
        assertEquals(current, WatchedSuburbs.toggle(current, ""))
    }

    @Test
    fun `toggling twice is a round trip`() {
        val current = setOf("AVONDALE")
        val once = WatchedSuburbs.toggle(current, "Henderson")
        assertEquals(current, WatchedSuburbs.toggle(once, "henderson"))
    }

    @Test
    fun `isWatched matches whatever case is stored`() {
        assertTrue(WatchedSuburbs.isWatched(setOf("Henderson"), "HENDERSON"))
        assertTrue(WatchedSuburbs.isWatched(setOf("HENDERSON"), "henderson"))
        assertFalse(WatchedSuburbs.isWatched(setOf("HENDERSON"), "PAKURANGA"))
        assertFalse(WatchedSuburbs.isWatched(emptySet(), "HENDERSON"))
    }
}
