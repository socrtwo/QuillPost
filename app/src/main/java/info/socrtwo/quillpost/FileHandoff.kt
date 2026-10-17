package info.socrtwo.quillpost

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import info.socrtwo.quillpost.web.OpenedFiles
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Files moving between QuillPost and other apps:
 *  - "Open with QuillPost" and "Share to QuillPost" (ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE):
 *    the files are read and queued in the in-process backend ([OpenedFiles.enqueue]); the web UI
 *    picks them up (`/api/file/pending`) and shows each one — a message in the reader window, a
 *    document with "Open in QuillPen" and "Send as attachment";
 *  - "Open in QuillPen / QuillGrid / QuillStage": the opened file is copied to the cache and handed
 *    to the suite app with a content:// URI (or to any app that can view it).
 */
object FileHandoff {
    private const val MAX_BYTES = 200L * 1024 * 1024

    /** The content URIs [intent] asks QuillPost to open (none for mailto: links and plain text shares). */
    fun uris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" })
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    /** Reads the files of [intent] off the main thread, queues them in the backend, then runs [done] on the main thread. */
    fun receive(activity: Activity, intent: Intent, done: () -> Unit) {
        val list = uris(intent)
        if (list.isEmpty()) return
        thread(name = "quillpost-open-file") {
            var n = 0
            for (uri in list) {
                try {
                    val name = displayName(activity, uri)
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) { val r = input.read(buf); if (r < 0) break; out.write(buf, 0, r); if (out.size() > MAX_BYTES) throw IllegalStateException("larger than 200 MB") }
                        out.toByteArray()
                    } ?: continue
                    OpenedFiles.enqueue(name, bytes)
                    n++
                } catch (e: Exception) {
                    activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.open_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
                }
            }
            if (n > 0) activity.runOnUiThread(done)
        }
    }

    private fun displayName(activity: Activity, uri: Uri): String {
        runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    }

    /**
     * Downloads [url] (the local backend's copy of an opened file) and opens it in the app [pkg]
     * (QuillPen, QuillGrid or QuillStage); when that app is missing, offers every app that can view it.
     */
    fun openInApp(activity: Activity, url: String, fileName: String, mimeType: String, pkg: String) {
        thread(name = "quillpost-open-in-app") {
            try {
                val bytes = (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 5000; readTimeout = 60_000
                    try { if (responseCode != 200) throw IllegalStateException("HTTP $responseCode"); inputStream.use { it.readBytes() } } finally { disconnect() }
                }
                val dir = File(activity.cacheDir, "open").apply { mkdirs() }
                dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
                val f = File(dir, fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" })
                f.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", f)
                val type = mimeType.ifBlank { "application/octet-stream" }
                activity.runOnUiThread {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        activity.startActivity(Intent(view).setPackage(pkg))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(activity, activity.getString(R.string.suite_app_missing, appLabel(pkg)), Toast.LENGTH_LONG).show()
                        try { activity.startActivity(Intent.createChooser(view, fileName)) }
                        catch (_: ActivityNotFoundException) { Toast.makeText(activity, R.string.no_viewer, Toast.LENGTH_SHORT).show() }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.open_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun appLabel(pkg: String) = when (pkg) {
        "io.github.socrtwo.quillgrid" -> "QuillGrid"
        "io.github.socrtwo.quillstage" -> "QuillStage"
        else -> "QuillPen"
    }
}
