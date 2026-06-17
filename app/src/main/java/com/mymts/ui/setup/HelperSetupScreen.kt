package com.mymts.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.helper.HelperClient
import com.mymts.data.helper.HelperUrl
import kotlinx.coroutines.launch

/**
 * Runtime helper-URL setup — the make-or-break distribution screen. Shown when no
 * helper URL resolves (a stock APK: no persisted value + an unconfigured BuildConfig
 * default) and reused as the Settings "Helper URL" editor. The operator's own build
 * resolves its configured URL and never sees this — no regression.
 *
 * TV/D-pad-friendly: a single URL field (the system/leanback IME handles entry) and a
 * "Test & connect" action that runs a bounded `/health` reachability probe (validating
 * the address is actually a MyMTS helper) BEFORE persisting — never a blank/broken wall.
 *
 * @param initialUrl pre-filled value (the resolved/BuildConfig default, so a demo user
 *   can just confirm localhost; or the current URL when re-editing from Settings).
 * @param cancellable true from the Settings editor (Back returns to the wall); false on
 *   genuine first-run (there's nowhere to go back to until a helper is set).
 * @param onConnected called with the normalized, reachability-verified URL to persist.
 */
@Composable
fun HelperSetupScreen(
    initialUrl: String,
    cancellable: Boolean,
    onConnected: (String) -> Unit,
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf(initialUrl) }
    var status by remember { mutableStateOf<Status>(Status.Idle) }
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }

    fun test() {
        val normalized = HelperUrl.normalize(text)
        if (normalized == null) {
            status = Status.Error("Enter a helper address — e.g. http://192.168.1.10:8091")
            return
        }
        status = Status.Testing
        scope.launch {
            status = when (val r = HelperClient(normalized).checkHealth()) {
                is HelperClient.Result.Ok -> {
                    onConnected(normalized)
                    Status.Ok
                }
                is HelperClient.Result.Err -> Status.Error(friendlyError(r.cause))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0D)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).padding(32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "Connect to your MyMTS helper",
                color = Color.White,
                fontSize = 26.sp,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter your helper's LAN address. Include the scheme for HTTPS " +
                    "(e.g. https://nas.local:8443); a plain host defaults to http.",
                color = Color(0xFFB8B8BE),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; if (status != Status.Testing) status = Status.Idle },
                singleLine = true,
                label = { Text("Helper URL") },
                placeholder = { Text("http://192.168.1.10:8091") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { test() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
            )
            Spacer(Modifier.height(12.dp))
            StatusLine(status)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::test, enabled = status != Status.Testing) {
                    Text(if (status == Status.Testing) "Testing…" else "Test & connect")
                }
                if (cancellable) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }

    LaunchedEffect(Unit) { fieldFocus.requestFocus() }
}

@Composable
private fun StatusLine(status: Status) {
    when (status) {
        Status.Idle -> Spacer(Modifier.height(20.dp))
        Status.Testing -> Text("Testing reachability…", color = Color(0xFFB8B8BE), fontSize = 14.sp)
        Status.Ok -> Text("Connected ✓", color = Color(0xFF5BD08A), fontSize = 14.sp)
        is Status.Error -> Text(status.message, color = Color(0xFFE06C6C), fontSize = 14.sp)
    }
}

/** Map a reachability failure to a short, actionable line (no stack traces on a TV). */
private fun friendlyError(cause: Throwable): String {
    val msg = cause.message.orEmpty()
    return when {
        "not a MyMTS helper" in msg -> "Reached a server, but it's not a MyMTS helper. Check the address."
        "timed out" in msg.lowercase() || "timeout" in msg.lowercase() ->
            "No response — check the address and that the helper is running."
        else -> "Couldn't reach a MyMTS helper at that address. Check it and try again."
    }
}

private sealed interface Status {
    data object Idle : Status
    data object Testing : Status
    data object Ok : Status
    data class Error(val message: String) : Status
}
