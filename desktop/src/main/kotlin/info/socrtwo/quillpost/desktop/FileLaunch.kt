package info.socrtwo.quillpost.desktop

import info.socrtwo.quillpost.web.OpenedFiles
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.prefs.Preferences

/**
 * Opening files with the desktop app: file arguments (Windows and Linux "Open with", the file
 * associations set in build.gradle.kts), macOS open-file events, a second launch handing its
 * files to the running instance, and "Open in Anne's Pen / Anne's Grid / Anne's Stage", which hands an
 * opened document to the program the operating system has registered for it.
 */
object FileLaunch {
    private const val PORT_KEY = "runningPort"
    private const val SECRET_KEY = "handoffKey"

    /** Gives [files] to an Anne's Post already running for this user; true when it took them. */
    fun handToRunning(prefs: Preferences, files: List<File>): Boolean {
        val port = prefs.getInt(PORT_KEY, 0)
        val key = prefs.get(SECRET_KEY, null)
        if (port <= 0 || key.isNullOrBlank()) return false
        val ok = files.all { f ->
            runCatching {
                val c = URI("http://127.0.0.1:$port/api/file/handoff?name=" + URLEncoder.encode(f.name, "UTF-8")).toURL().openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 2000; c.readTimeout = 30_000
                c.setRequestProperty("X-QuillPost-Handoff", key)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { it.write(f.readBytes()) }
                val code = c.responseCode
                c.disconnect()
                code == 200
            }.getOrDefault(false)
        }
        if (ok) runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("http://127.0.0.1:$port/")) }
        return ok
    }

    /** Queues [files] for the UI, lets later launches hand files over, and installs the system handlers. */
    fun install(prefs: Preferences, port: Int, files: List<File>, showUi: () -> Unit) {
        for (f in files) runCatching { OpenedFiles.enqueue(f.name, f.readBytes()) }
        val key = (1..32).map { "abcdefghijklmnopqrstuvwxyz0123456789"[java.security.SecureRandom().nextInt(36)] }.joinToString("")
        OpenedFiles.handoffKey = key
        prefs.put(SECRET_KEY, key)
        prefs.putInt(PORT_KEY, port)
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.OPEN)) OpenedFiles.nativeOpener = { f -> runCatching { desktop.open(f); true }.getOrDefault(false) }
        // macOS delivers double-clicked files as events, not arguments.
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) runCatching {
            desktop.setOpenFileHandler { e -> e.files.filter { it.isFile }.forEach { f -> runCatching { OpenedFiles.enqueue(f.name, f.readBytes()) } }; showUi() }
        }
    }

    fun uninstall(prefs: Preferences) {
        runCatching { prefs.remove(PORT_KEY); prefs.remove(SECRET_KEY) }
    }
}
