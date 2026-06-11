package com.mymts.ui.wall

/**
 * Pure geometry for placing a video tile's name label using the video's REAL
 * rendered dimensions (2026-06-11). `RESIZE_MODE_FIT` centres the stream in the
 * cell, leaving equal letterbox bars top + bottom (cell taller than the video)
 * or pillarbox bars left + right (cell wider). The label goes in the black bar
 * BELOW the picture — the operator's original aesthetic — using these dims so it
 * lands in the visible area, not the overscan-clipped tile edge.
 *
 * No Compose/Android — unit-tested in `TileLabelTest`. All lengths in one unit
 * (the caller passes consistent dp).
 */
object TileLabel {

    enum class Placement { BELOW, ABOVE, OVERLAY }

    /** Rendered video height under RESIZE_MODE_FIT (fit within the cell). */
    fun renderedVideoHeight(cellW: Float, cellH: Float, videoAspect: Float): Float {
        val cellAspect = cellW / cellH
        // Cell wider than the video → fit to height (full height, pillarbox).
        // Cell taller → fit to width (letterbox bars top + bottom).
        return if (cellAspect > videoAspect) cellH else cellW / videoAspect
    }

    /** Y (from the cell top) of the rendered video's bottom / top edge. */
    fun videoBottom(cellH: Float, videoH: Float): Float = (cellH + videoH) / 2f
    fun videoTop(cellH: Float, videoH: Float): Float = (cellH - videoH) / 2f

    /**
     * Where to put the label. Priority **below → above → overlay**:
     *  - **BELOW**: the bottom letterbox comfortably fits the label AND clears
     *    the overscan-clipped tile edge (bar ≥ labelSlot + clipSafe). The label
     *    sits just under the picture, on black — the original look.
     *  - **ABOVE**: there's a letterbox but it's tight at the bottom (would risk
     *    the clip) → use the matching TOP bar, away from the bottom edge.
     *  - **OVERLAY**: no usable letterbox (pillarbox, or the size isn't known
     *    yet) → a tinted bubble over the video, lifted into the safe area.
     */
    fun placement(
        cellW: Float,
        cellH: Float,
        videoAspect: Float?,
        labelSlot: Float,
        clipSafe: Float,
    ): Placement {
        if (videoAspect == null || videoAspect <= 0f || cellW <= 0f || cellH <= 0f) return Placement.OVERLAY
        val videoH = renderedVideoHeight(cellW, cellH, videoAspect)
        val letterbox = (cellH - videoH) / 2f
        return when {
            letterbox >= labelSlot + clipSafe -> Placement.BELOW
            letterbox >= labelSlot -> Placement.ABOVE
            else -> Placement.OVERLAY
        }
    }
}
