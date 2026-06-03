package com.mymts.ui.menu

import androidx.compose.ui.graphics.Color

/**
 * Menu palette — translucent dark over the wall, green focus accent.
 * Kept tight against WyzeGrid's vocabulary so the two apps read as
 * the same family on the same TV.
 */
internal object MenuColors {
    val Scrim = Color(0xCC000000)
    val PanelBackground = Color(0xF21A1A1A)
    val PanelDivider = Color(0x22FFFFFF)

    val FocusAccent = Color(0xFF66BB6A)
    val FocusBackground = Color(0x3366BB6A)

    val RowLabel = Color(0xCCFFFFFF)
    val RowLabelMuted = Color(0x99FFFFFF)
    val RowDetail = Color(0xCC66BB6A)
    val RowDetailMuted = Color(0x99FFCC80)
    val RowDetailOffline = Color(0xCC9E9E9E)

    val Footer = Color(0x66FFFFFF)
}
