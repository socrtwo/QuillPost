package info.socrtwo.quillbox

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * Android side of the QuillPen embedded editor (the suite's EMBED.md, section 1).
 *
 * QuillPen (applicationId [PACKAGE]) exports a read-only ContentProvider, authority [AUTHORITY],
 * that serves its built web files by path. The compose window loads
 * `https://quillpen.embed/index.html?embed=compose&host=quillpost&v=1` in an iframe; the WebView
 * hands every request to that host to [intercept], which answers it from the provider. The page
 * therefore runs on its own origin (`https://quillpen.embed`), separate from QuillPost's.
 */
object QuillPenAndroid {
    const val PACKAGE = "io.github.socrtwo.quillpen"
    const val AUTHORITY = "io.github.socrtwo.quillpen.embed"
    const val HOST = "quillpen.embed"

    @Volatile private var cached: Boolean? = null

    /** QuillPen is installed and its provider answers index.html. Cached until [forget]. */
    fun isAvailable(context: Context): Boolean {
        cached?.let { return it }
        val ok = isInstalled(context) && runCatching {
            context.contentResolver.openInputStream(providerUri("index.html"))?.use { it.read() >= 0 } ?: false
        }.getOrDefault(false)
        cached = ok
        return ok
    }

    /** Re-detect on the next call (for example when the app comes back to the foreground). */
    fun forget() { cached = null }

    private fun isInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun providerUri(path: String): Uri =
        Uri.Builder().scheme("content").authority(AUTHORITY).apply { path.split('/').forEach { appendPath(it) } }.build()

    fun isEmbedUrl(uri: Uri?): Boolean = uri != null && uri.scheme == "https" && uri.host == HOST

    /**
     * Answers a WebView request for `https://quillpen.embed/<path>` from QuillPen's provider, or
     * returns null for any other URL. Path segments are checked before they reach the provider
     * (no `..`, no hidden files), the query string is ignored, and the MIME type comes from the
     * file extension (JavaScript modules are refused by the WebView without it).
     */
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (!isEmbedUrl(url)) return null
        if (request.method != "GET") return error(405, "Method Not Allowed")
        val segments = url.pathSegments.filter { it.isNotEmpty() }.ifEmpty { listOf("index.html") }
        if (segments.any { it == "." || it == ".." || it.startsWith(".") || it.contains('/') || it.contains('\\') || it.contains('\u0000') }) return error(404, "Not Found")
        val path = segments.joinToString("/")
        return try {
            val stream = context.contentResolver.openInputStream(providerUri(path)) ?: return error(404, "Not Found")
            val mime = mimeType(path)
            val text = mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml"
            WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", mapOf("Cache-Control" to "no-cache", "X-Content-Type-Options" to "nosniff"), stream)
        } catch (_: FileNotFoundException) {
            error(404, "Not Found")
        } catch (_: SecurityException) {
            error(403, "Forbidden")
        } catch (_: Exception) {
            error(500, "Internal Error")
        }
    }

    fun mimeType(path: String): String {
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs", "cjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "webmanifest" -> "application/manifest+json"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun error(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(ByteArray(0)))
}
