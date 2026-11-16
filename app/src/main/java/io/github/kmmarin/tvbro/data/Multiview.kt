// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

/**
 * Multiview layouts, each for an exact number of tiles (never an empty tile): side by side (2), 1 + 2 and three in a
 * row (3), 2 x 2 and 1 + 3 (4). [SINGLE]: one tile left, full size.
 */
enum class MvLayout(val label: String, val tiles: Int) {
    SINGLE("Full", 1), SIDE("Side by side", 2), ONE_PLUS_TWO("1 + 2", 3), ROW3("3 side by side", 3), GRID("2 x 2", 4), ONE_PLUS_THREE("1 + 3", 4)
}

/** A tile's place on screen as fractions of the screen (0..1). */
data class MvRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Multiview rules, plain JVM and unit-tested: how many tiles each provider's stream limit allows, the layouts for a
 * number of tiles, where the tiles sit, where the D-pad moves the focus, and the largest video each tile needs.
 */
object MvRules {
    const val MAX_TILES = 4

    /** The layouts for [n] tiles (the first is the default). */
    fun layoutsFor(n: Int): List<MvLayout> = MvLayout.values().filter { it.tiles == n.coerceIn(1, MAX_TILES) }

    /** [pref] when it is a layout for [n] tiles, else the default one for [n]. */
    fun resolve(pref: MvLayout, n: Int): MvLayout = pref.takeIf { it.tiles == n } ?: layoutsFor(n).first()

    /** The next layout for the same number of tiles (null: there is only one). */
    fun nextLayout(cur: MvLayout): MvLayout? {
        val l = layoutsFor(cur.tiles)
        return if (l.size < 2) null else l[(l.indexOf(cur) + 1) % l.size]
    }

    /**
     * True when a channel of provider [src] can go into the multiview whose tiles come from [tiles] (source ids, in tile
     * order): into slot [slot] (replacing that tile) or as a new tile ([slot] null). Every tile is one connection of its
     * own provider, and each provider's tiles stay within [free] (its limit minus its recordings); at most [MAX_TILES].
     */
    fun fits(tiles: List<String>, slot: Int?, src: String, free: (String) -> Int): Boolean {
        val others = if (slot != null && slot in tiles.indices) tiles.filterIndexed { i, _ -> i != slot } else tiles
        return others.size < MAX_TILES && others.count { it == src } < free(src)
    }

    /** The channels (by source id) that fit, in order: those past their provider's limit are left out. */
    fun <T> take(chs: List<T>, src: (T) -> String, free: (String) -> Int): List<T> {
        val out = ArrayList<T>()
        for (c in chs) if (fits(out.map(src), null, src(c), free)) out += c
        return out
    }

    /** Tiles the providers of [sources] allow together (multiview needs 2). */
    fun room(sources: Collection<String>, free: (String) -> Int): Int = sources.distinct().sumOf { free(it) }.coerceAtMost(MAX_TILES)

    /** Tile rectangles of [layout] for [n] tiles (the layout is [resolve]d for [n]). */
    fun rects(layout: MvLayout, n: Int): List<MvRect> {
        val third = 1f / 3f
        return when (resolve(layout, n)) {
            MvLayout.SINGLE -> listOf(MvRect(0f, 0f, 1f, 1f))
            MvLayout.SIDE -> listOf(MvRect(0f, 0.25f, 0.5f, 0.5f), MvRect(0.5f, 0.25f, 0.5f, 0.5f))
            // big tile: 2/3 of the width on the left; the two small ones (1/3 each way) stacked beside it
            MvLayout.ONE_PLUS_TWO -> listOf(MvRect(0f, 1f / 6f, 2 * third, 2 * third), MvRect(2 * third, 1f / 6f, third, third), MvRect(2 * third, 0.5f, third, third))
            MvLayout.ROW3 -> (0 until 3).map { MvRect(it * third, third, third, third) }
            MvLayout.GRID -> (0 until 4).map { MvRect((it % 2) * 0.5f, (it / 2) * 0.5f, 0.5f, 0.5f) }
            // big tile: 2/3 of the width; the small ones stack in the right third (each 1/3 high)
            MvLayout.ONE_PLUS_THREE -> listOf(MvRect(0f, 1f / 6f, 2 * third, 2 * third)) + (0 until 3).map { MvRect(2 * third, it * third, third, third) }
        }
    }

    enum class Dir { LEFT, RIGHT, UP, DOWN }

    /**
     * The tile the D-pad moves to from [from] (or [from] itself at an edge): the nearest tile whose centre lies in that
     * direction, nearer in the cross direction first.
     */
    fun move(layout: MvLayout, n: Int, from: Int, dir: Dir): Int {
        val r = rects(layout, n)
        if (from !in r.indices) return 0
        val c = r[from]
        val cx = c.x + c.w / 2; val cy = c.y + c.h / 2
        return r.indices.filter { it != from }.map { i ->
            val o = r[i]; val ox = o.x + o.w / 2; val oy = o.y + o.h / 2
            val (along, cross) = when (dir) {
                Dir.LEFT -> cx - ox to kotlin.math.abs(cy - oy)
                Dir.RIGHT -> ox - cx to kotlin.math.abs(cy - oy)
                Dir.UP -> cy - oy to kotlin.math.abs(cx - ox)
                Dir.DOWN -> oy - cy to kotlin.math.abs(cx - ox)
            }
            Triple(i, along, cross)
        }.filter { it.second > 0.01f }.minWithOrNull(compareBy({ it.third }, { it.second }))?.first ?: from
    }

    /**
     * Largest video size worth decoding for tile [i] on a [screenH]-pixel-high screen: the tile's own height rounded up
     * to a usual rung (540p for quarter tiles on 1080p, 720p for the big tile). Fire TV Stick 4K decoders manage two
     * HD streams; four 1080p streams drop frames.
     */
    fun maxVideoHeight(layout: MvLayout, n: Int, i: Int, screenH: Int = 1080): Int {
        val r = rects(layout, n).getOrNull(i) ?: return 1080
        val px = (r.h * screenH).toInt()
        return listOf(360, 540, 720, 1080).firstOrNull { it >= px } ?: 1080
    }
}
