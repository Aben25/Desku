package com.deskbuddy.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.deskbuddy.DeskPage
import com.deskbuddy.Mode
import com.deskbuddy.UiState

/**
 * A page Desku put on the screen, full-screen and in-app. Pages are sandboxed HTML from the
 * engine, so navigation is kept to the engine's own address; anything else is refused. A strip
 * at the bottom keeps Desku and the live caption visible while the page is up.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PageOverlay(page: DeskPage, ui: UiState, mode: Mode, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val origin = remember(page.url) { Uri.parse(page.url).let { "${it.scheme}://${it.authority}" } }
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }
    DisposableEffect(Unit) { onDispose { web.destroy() } }

    Column(Modifier.fillMaxSize().background(Night.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(page.title, style = fig(20.sp, 600), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.clip(CircleShape).background(Night.Chip).clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 9.dp),
            ) { Text("Close", style = fig(16.sp, 500, Night.Text)) }
        }
        AndroidView(
            factory = { web },
            update = { v ->
                v.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        return "${u.scheme}://${u.authority}" != origin // stay on the engine's pages
                    }
                }
                if (v.url != page.url) v.loadUrl(page.url)
            },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)),
        )
        // Desku stays with you while the page is up: his face and what he's hearing or saying.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Desku(
                when (mode) {
                    Mode.Speaking -> Expression.Talking
                    Mode.Thinking -> Expression.Thinking
                    Mode.Listening -> Expression.Listening
                    else -> Expression.Dozing
                },
                56.dp, halo = false, rings = false, level = if (mode == Mode.Speaking) ui.voiceLevel else 0f, voiceDriven = true,
            )
            Spacer(Modifier.width(14.dp))
            val hearing = ui.heard.isNotBlank()
            Text(
                when {
                    hearing -> "Hearing: “${ui.heard}”"
                    ui.said.isNotBlank() -> ui.said.takeLast(140)
                    else -> "Tap Close when you're done."
                },
                style = fig(16.sp, if (hearing) 500 else 400, if (hearing) Night.Text else Night.Sub, line = 22.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
