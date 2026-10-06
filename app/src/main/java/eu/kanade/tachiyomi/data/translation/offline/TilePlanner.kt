package eu.kanade.tachiyomi.data.translation.offline

import kotlin.math.max
import kotlin.math.min

/**
 * Cuts a tall page into overlapping strips for text recognition. Webtoon pages can be 10 000 px tall, which is far
 * more than the recognizer handles well and more than is safe to hold in memory at once.
 *
 * Every row of the page belongs to exactly one strip ([Tile.ownTop] until [Tile.ownBottom]). A text block is kept
 * only by the strip that owns its centre, so a block seen by two overlapping strips is not translated twice.
 */
object TilePlanner {

    private const val MIN_TILE_HEIGHT = 1600
    private const val MAX_TILE_HEIGHT = 3200
    private const val WIDTH_FACTOR = 2

    data class Tile(val top: Int, val bottom: Int, val ownTop: Int, val ownBottom: Int) {
        val height: Int get() = bottom - top

        fun owns(centerY: Int): Boolean = centerY in ownTop until ownBottom
    }

    fun tileHeightFor(width: Int): Int = (width * WIDTH_FACTOR).coerceIn(MIN_TILE_HEIGHT, MAX_TILE_HEIGHT)

    fun plan(width: Int, height: Int): List<Tile> {
        if (height <= 0) return emptyList()
        val tileHeight = tileHeightFor(width)
        if (height <= tileHeight) return listOf(Tile(0, height, 0, height))

        val overlap = tileHeight / 4
        val step = tileHeight - overlap
        val ranges = mutableListOf<Pair<Int, Int>>()
        var top = 0
        while (true) {
            val bottom = min(height, top + tileHeight)
            if (bottom >= height) {
                // The last strip keeps the full height by starting earlier, instead of being a thin leftover.
                ranges += max(0, height - tileHeight) to height
                break
            }
            ranges += top to bottom
            top += step
        }

        // Strips meet in the middle of the overlap with their neighbour.
        val boundaries = (0 until ranges.size - 1).map { (ranges[it + 1].first + ranges[it].second) / 2 }
        return ranges.mapIndexed { i, (tileTop, tileBottom) ->
            Tile(
                top = tileTop,
                bottom = tileBottom,
                ownTop = if (i == 0) 0 else boundaries[i - 1],
                ownBottom = if (i == ranges.size - 1) height else boundaries[i],
            )
        }
    }
}
