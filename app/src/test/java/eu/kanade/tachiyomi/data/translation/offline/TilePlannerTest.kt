package eu.kanade.tachiyomi.data.translation.offline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TilePlannerTest {

    @Test
    fun `short page is one tile that owns everything`() {
        val tiles = TilePlanner.plan(800, 1200)
        assertEquals(listOf(TilePlanner.Tile(0, 1200, 0, 1200)), tiles)
    }

    @Test
    fun `empty page has no tiles`() {
        assertTrue(TilePlanner.plan(800, 0).isEmpty())
    }

    @Test
    fun `every row of a tall page is owned by exactly one tile`() {
        for (height in listOf(3201, 4000, 9999, 12345, 30000)) {
            val tiles = TilePlanner.plan(800, height)
            assertEquals(0, tiles.first().ownTop)
            assertEquals(height, tiles.last().ownBottom)
            for (i in 1 until tiles.size) assertEquals(tiles[i - 1].ownBottom, tiles[i].ownTop)
            for (y in 0 until height step 7) {
                assertEquals(1, tiles.count { it.owns(y) }, "row $y of $height")
            }
        }
    }

    @Test
    fun `owned rows stay inside their tile and tiles stay inside the page`() {
        val tiles = TilePlanner.plan(1000, 20000)
        for (tile in tiles) {
            assertTrue(tile.top >= 0 && tile.bottom <= 20000)
            assertTrue(tile.ownTop >= tile.top && tile.ownBottom <= tile.bottom)
            assertTrue(tile.ownBottom > tile.ownTop)
            assertTrue(tile.height <= TilePlanner.tileHeightFor(1000))
        }
    }

    @Test
    fun `tiles overlap so text cut by one edge is whole in the next`() {
        val tiles = TilePlanner.plan(800, 10000)
        for (i in 1 until tiles.size) assertTrue(tiles[i].top < tiles[i - 1].bottom)
    }
}
