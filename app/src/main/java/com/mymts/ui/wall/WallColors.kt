package com.mymts.ui.wall

import androidx.compose.ui.graphics.Color

/**
 * Wall palette — dark, dense newsroom feel. Keep this central so the
 * grid, the future feed pane, and the ticker share one consistent
 * visual register from Stage 3 onward.
 */
object WallColors {
    val Background = Color(0xFF000000)
    val TileGap = Color(0xFF050505)
    val DeadTile = Color(0xFF0A0A0A)
    val EmptyTile = Color(0xFF080808)

    val LabelPrimary = Color(0xCCFFFFFF)
    val LabelMuted = Color(0x99FFFFFF)
    val LabelGhost = Color(0x66FFFFFF)

    val BadgeLive = Color(0xFF66BB6A)
    val BadgeConnecting = Color(0xFFFFEE58)
    val BadgeStale = Color(0xFFFFCC80)
    val BadgeRecovering = Color(0xFFFFA726)
    val BadgeDead = Color(0xFF9E9E9E)
    val BadgeOffline = Color(0xFF616161)
}
