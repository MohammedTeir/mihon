package eu.kanade.tachiyomi.data.translation.overlay

/**
 * Gemini sometimes returns several boxes for one bubble (one per line or sentence). Drawing each separately would
 * erase and overlap the others, so boxes whose centre lies inside another box's bubble are merged into one group.
 */
object BubbleGrouping {

    /**
     * @return groups of indexes into [boxes], each group sorted ascending and groups ordered by first index.
     */
    fun group(boxes: List<PixelRect>, regions: List<BubbleRegion>): List<List<Int>> {
        require(boxes.size == regions.size)
        val parent = IntArray(boxes.size) { it }

        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val iHoldsJ = regions[i].contains(boxes[j].centerX, boxes[j].centerY)
                val jHoldsI = regions[j].contains(boxes[i].centerX, boxes[i].centerY)
                if (iHoldsJ || jHoldsI) parent[find(j)] = find(i)
            }
        }
        return boxes.indices.groupBy { find(it) }.values.sortedBy { it.first() }
    }
}
