package com.mymts.ui.wall

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Panel-fit calibration overlay (2026-06-09). Draws a bright boundary line +
 * thick corner brackets + labelled corners (TL / TR / BL / BR) at the EXACT
 * edge of the wall content area.
 *
 * Why: a physically-overscanning panel crops the outer edge of the frame, and
 * the operator CANNOT otherwise tell what's being cut off (it's off-screen by
 * definition). With this on, "fits" is unambiguous: if the complete rectangle
 * border + all four corner brackets + all four labels are visible, nothing is
 * cropped. If (say) the bottom and right lines and the "BR" corner are missing,
 * that corner is being cut — raise the Overscan inset (or nudge the offset)
 * until it appears, then turn this off.
 *
 * Placed as a sibling of the wall Column INSIDE the inset Box but OUTSIDE the
 * LocalDensity override, so it fills the true wall content rectangle (after the
 * position offset + overscan inset) and draws in real screen pixels — the line
 * sits where the content edge actually is.
 */
@Composable
fun CalibrationOverlay(modifier: Modifier = Modifier) {
    val edge = Color(0xFFFF2D95)   // hot magenta — the boundary rectangle
    val corner = Color(0xFF00E5FF) // cyan — corner brackets + labels

    Box(modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val sw = 4.dp.toPx()
            val half = sw / 2f
            // Boundary rectangle, inset by half a stroke so the whole line sits
            // inside the content area — any missing segment == a cropped edge.
            drawRect(
                color = edge,
                topLeft = Offset(half, half),
                size = Size(w - sw, h - sw),
                style = Stroke(width = sw),
            )
            // Thick L-brackets at each corner — unmistakable even over busy
            // chrome, and the precise indicator of whether the very corner shows.
            val len = 52.dp.toPx()
            val bw = 8.dp.toPx()
            val o = bw / 2f
            fun bracket(x0: Float, y0: Float, dx: Int, dy: Int) {
                drawLine(corner, Offset(x0, y0), Offset(x0 + dx * len, y0), bw)
                drawLine(corner, Offset(x0, y0), Offset(x0, y0 + dy * len), bw)
            }
            bracket(o, o, +1, +1)             // top-left
            bracket(w - o, o, -1, +1)         // top-right
            bracket(o, h - o, +1, -1)         // bottom-left
            bracket(w - o, h - o, -1, -1)     // bottom-right
        }
        // Corner identifiers so the operator can name the missing edge.
        CornerLabel("TL", Alignment.TopStart, edge, corner)
        CornerLabel("TR", Alignment.TopEnd, edge, corner)
        CornerLabel("BL", Alignment.BottomStart, edge, corner)
        CornerLabel("BR", Alignment.BottomEnd, edge, corner)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerLabel(
    text: String,
    alignment: Alignment,
    bg: Color,
    fg: Color,
) {
    Text(
        text = text,
        color = fg,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(alignment)
            .padding(14.dp)
            .background(bg.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
