package com.mymts.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Stage 1 default screen — confirms the build runs on the box and surfaces
 * the build-identity facts (Op Bar C3 — health is legible at a glance).
 *
 * This is also the analog of the helper's /health response: someone glancing
 * at the TV in dev should be able to read off version + SHA + the configured
 * max-tiles default without digging into adb logcat.
 */
@Composable
fun PlaceholderScreen(
    version: String,
    buildSha: String,
    defaultMaxTiles: Int,
) {
    var uptimeSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            uptimeSeconds = (System.currentTimeMillis() - start) / 1000
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "MyMTS",
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Stage 1 skeleton — build runs, ADB reaches the box.",
                color = Color(0xFFAAAAAA),
                fontSize = 22.sp,
            )
            Text(
                text = "Activate the soak harness:",
                color = Color(0xFF66BB6A),
                fontSize = 20.sp,
            )
            Text(
                text = "adb shell am start -n com.mymts/.MainActivity --es mode soak --ei tiles 4",
                color = Color(0xFFBBBBBB),
                fontSize = 16.sp,
            )
            Text(
                text = " ",
                color = Color.Transparent,
                fontSize = 12.sp,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "version $version · sha $buildSha · default max-tiles $defaultMaxTiles · up ${uptimeSeconds}s",
                color = Color(0xFF888888),
                fontSize = 16.sp,
            )
        }
    }
}
