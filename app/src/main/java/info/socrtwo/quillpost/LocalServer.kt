package info.socrtwo.quillpost

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import info.socrtwo.quillpost.web.module
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Runs the Anne's Post backend (the same Ktor module as the web and desktop clients) inside the
 * app process, bound to 127.0.0.1 only. The WebView in [MainActivity] loads its Outlook-style
 * UI from here, so the phone needs no separate server.
 *
 * The port is kept stable across restarts (persisted in SharedPreferences) because the web
 * UI stores the signed-in account in `localStorage`, which is scoped to host + port.
 */
object LocalServer {
    private const val TAG = "QuillPost"
    private const val PREFS = "quillpost-server"
    private const val PREFERRED_PORT = 8642

    @Volatile var url: String? = null
        private set
    @Volatile var error: String? = null
        private set

    private var server: EmbeddedServer<*, *>? = null
    private var starting = false
    private val waiting = ArrayList<(String?) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Starts the server if needed and calls [onReady] on the main thread with the base URL (or null on failure). */
    @Synchronized
    fun start(context: Context, onReady: (String?) -> Unit) {
        val app = context.applicationContext
        val existing = url
        if (existing != null) {
            // Already running — but verify it still answers (the system may have reclaimed
            // parts of the process while the Activity was gone) before reusing it.
            thread(name = "quillpost-server-check", isDaemon = true) {
                if (waitUntilUp(existing, 2_000)) main.post { onReady(existing) }
                else {
                    Log.w(TAG, "Backend at $existing stopped answering; restarting")
                    synchronized(this) { runCatching { server?.stop(200, 500) }; server = null; url = null }
                    start(app, onReady)
                }
            }
            return
        }
        waiting += onReady
        if (starting) return
        starting = true
        error = null
        System.setProperty("quillpost.dataDir", File(app.filesDir, "quillpost").absolutePath)
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "false")
        // Jakarta Mail resolves its providers through the context class loader.
        thread(name = "quillpost-server", isDaemon = true) {
            Thread.currentThread().contextClassLoader = app.classLoader
            try {
                val port = choosePort(app)
                val s = embeddedServer(CIO, port = port, host = "127.0.0.1", module = Application::module)
                s.start(wait = false)
                server = s
                val base = "http://127.0.0.1:$port/"
                if (!waitUntilUp(base)) throw IllegalStateException("server did not answer on port $port")
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("port", port).apply()
                url = base
                Log.i(TAG, "Backend listening on $base")
                finish(base)
            } catch (e: Throwable) {
                Log.e(TAG, "Backend failed to start", e)
                error = e.message ?: e.javaClass.simpleName
                runCatching { server?.stop(200, 500) }
                server = null
                finish(null)
            }
        }
    }

    @Synchronized
    private fun finish(base: String?) {
        starting = false
        val listeners = ArrayList(waiting); waiting.clear()
        main.post { listeners.forEach { it(base) } }
    }

    private fun choosePort(context: Context): Int {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("port", PREFERRED_PORT)
        for (candidate in listOf(saved, PREFERRED_PORT) + (PREFERRED_PORT + 1..PREFERRED_PORT + 20)) {
            if (isFree(candidate)) return candidate
        }
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }

    private fun isFree(port: Int): Boolean =
        runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true } }.getOrDefault(false)

    private fun waitUntilUp(base: String, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                val c = URL(base).openConnection() as HttpURLConnection
                c.connectTimeout = 500; c.readTimeout = 1000
                try { c.responseCode == 200 } finally { c.disconnect() }
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(100)
        }
        return false
    }
}
