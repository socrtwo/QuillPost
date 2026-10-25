package info.socrtwo.quillpost.desktop

import info.socrtwo.quillpost.web.qpEnv
import info.socrtwo.quillpost.web.qpProp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import info.socrtwo.quillpost.web.QuillPenEmbed
import info.socrtwo.quillpost.web.module
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.ServerSocket
import java.net.URI
import java.util.prefs.Preferences

/**
 * Desktop launcher: starts the Anne's Post web backend on localhost and opens the Outlook-style
 * web UI in the default browser. The window shows the address, lets you reopen or copy it,
 * and the app keeps running from the system tray.
 */
fun main(args: Array<String>) {
    val prefs = Preferences.userRoot().node("info/socrtwo/quillpost/desktop")
    // Files the system asked Anne's Post to open (double-click, "Open with"): messages, contacts,
    // calendars, documents. When Anne's Post already runs, hand them to it and show its window.
    val files = args.map { java.io.File(it) }.filter { it.isFile }
    if (files.isNotEmpty() && FileLaunch.handToRunning(prefs, files)) return
    val port = System.getenv("PORT")?.toIntOrNull() ?: prefs.getInt("port", 8642).let { if (isFree(it)) it else freePort() }
    // Anne's Pen embedded editor: serve an installed Anne's Pen's resources/embed folder at /quillpen-embed/.
    QuillPenEmbed.desktop = true
    if (!GraphicsEnvironment.isHeadless()) QuillPenEmbed.folderChooser = ::chooseQuillPenFolder
    // Single-user app on 127.0.0.1: the backend may keep the AI tab's API keys in its private data folder.
    System.setProperty("quillpost.desktop", "true")
    val server = embeddedServer(Netty, port = port, host = "127.0.0.1") { module() }
    server.start(wait = false)
    val url = "http://127.0.0.1:$port/"
    FileLaunch.install(prefs, port, files) { openBrowser(url) }
    if (prefs.getBoolean("openOnStart", true)) openBrowser(url)

    application {
        val windowState = rememberWindowState(width = 520.dp, height = 300.dp)
        var visible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        Tray(
            state = trayState,
            icon = painterResource("tray.svg"),
            tooltip = "Anne's Post",
            menu = {
                Item("Open Anne's Post", onClick = { openBrowser(url) })
                Item("Show window", onClick = { visible = true })
                Separator()
                Item("Quit", onClick = { FileLaunch.uninstall(prefs); stop(server); exitApplication() })
            }
        )
        Window(
            onCloseRequest = { visible = false },
            visible = visible,
            state = windowState,
            title = "Anne's Post",
            icon = painterResource("tray.svg")
        ) {
            LauncherScreen(url = url, onQuit = { FileLaunch.uninstall(prefs); stop(server); exitApplication() })
        }
    }
}

@Composable
private fun LauncherScreen(url: String, onQuit: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Anne's Post is running", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("The mail client opens in your web browser. Closing this window keeps Anne's Post running in the tray; use Quit to stop it.",
                    style = MaterialTheme.typography.bodyMedium)
                Text(url, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openBrowser(url) }) { Text("Open Anne's Post") }
                    OutlinedButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null) }) { Text("Copy address") }
                    OutlinedButton(onClick = onQuit) { Text("Quit") }
                }
                Text("Data folder: " + (qpEnv("QUILLPOST_DATA_DIR") ?: (System.getProperty("user.home") + "/.quillpost")),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(URI(url))
        else {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                os.contains("mac") -> listOf("open", url)
                else -> listOf("xdg-open", url)
            }
            ProcessBuilder(cmd).start()
        }
    }
}

private fun stop(server: EmbeddedServer<*, *>) {
    runCatching { server.stop(500, 1500) }
}

private fun isFree(port: Int): Boolean = runCatching { ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { true } }.getOrDefault(false)

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Native folder picker for Settings > Compose > Anne's Pen folder (runs on the Swing thread). */
private suspend fun chooseQuillPenFolder(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    var chosen: String? = null
    javax.swing.SwingUtilities.invokeAndWait {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "Choose Anne's Pen's resources/embed folder"
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chosen = chooser.selectedFile?.absolutePath
    }
    chosen
}
