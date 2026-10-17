package info.socrtwo.quillbox.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Desktop side of the QuillPen embedded editor (see the suite's EMBED.md, section 1).
 *
 * The desktop launcher sets [desktop] (system property `quillpost.desktop=true`). The QuillPen
 * installer ships its web build unpacked in `resources/embed/`; this object finds it in the
 * standard install locations, or in a folder the person chose in Settings, and the routes serve
 * it at `/quillpen-embed/<path>` so the compose window can load it in an iframe.
 *
 * On the self-hosted web server the routes answer "not available" and serve nothing: there the
 * person enters the URL of a QuillPen web deployment instead (kept in the browser). On Android
 * the WebView shell answers `https://quillpen.embed/` itself, from QuillPen's ContentProvider.
 */
object QuillPenEmbed {
    /** True when this backend runs inside the desktop launcher (set before the server starts). */
    @Volatile var desktop: Boolean = System.getProperty("quillpost.desktop") == "true"

    /** Native folder picker installed by the desktop launcher; null when there is none (headless). */
    @Volatile var folderChooser: (suspend () -> String?)? = null

    private const val CHOSEN_FILE = "quillpen-folder.txt"

    /** The standard locations of QuillPen's `resources/embed` folder on this operating system. */
    fun standardLocations(
        os: String = System.getProperty("os.name") ?: "",
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home") ?: ""
    ): List<File> {
        val o = os.lowercase()
        return when {
            o.contains("win") -> listOfNotNull(
                env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "Programs\\QuillPen\\resources\\embed") },
                env("PROGRAMFILES")?.takeIf { it.isNotBlank() }?.let { File(it, "QuillPen\\resources\\embed") }
            )
            o.contains("mac") || o.contains("darwin") -> listOf(
                File("/Applications/QuillPen.app/Contents/Resources/embed"),
                File(home, "Applications/QuillPen.app/Contents/Resources/embed")
            )
            else -> listOf(File("/opt/QuillPen/resources/embed"))
        }
    }

    /** A folder holds QuillPen's embed build when it contains index.html. */
    fun isEmbedFolder(dir: File?): Boolean = dir != null && dir.isDirectory && File(dir, "index.html").isFile

    fun chosenFolder(dataDir: File): File? =
        runCatching { File(dataDir, CHOSEN_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) } }.getOrNull()

    fun setChosenFolder(dataDir: File, folder: String?) {
        val f = File(dataDir, CHOSEN_FILE)
        if (folder.isNullOrBlank()) f.delete() else { dataDir.mkdirs(); f.writeText(folder.trim()) }
    }

    fun detectedFolder(): File? = standardLocations().firstOrNull { isEmbedFolder(it) }

    /** The folder to serve: the chosen one when it is valid, else the first standard location found. */
    fun root(dataDir: File): File? = chosenFolder(dataDir)?.takeIf { isEmbedFolder(it) } ?: detectedFolder()

    /**
     * Maps the request path segments to a file inside [root], refusing anything that could leave
     * it: empty, `.` or `..` segments, separators or drive letters inside a segment, NUL bytes,
     * hidden files, and (after resolving symbolic links) any path outside the canonical root.
     * An empty path means index.html.
     */
    fun resolve(root: File, segments: List<String>): File? {
        val parts = segments.filter { it.isNotEmpty() }.ifEmpty { listOf("index.html") }
        for (p in parts) {
            if (p == "." || p == ".." || p.startsWith(".") || p.any { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) return null
        }
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(base, parts.joinToString(File.separator)).canonicalFile }.getOrNull() ?: return null
        if (!target.path.startsWith(base.path + File.separator)) return null
        return target.takeIf { it.isFile }
    }

    fun contentType(name: String): ContentType = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
        "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
        "js", "mjs", "cjs" -> ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
        "json", "map" -> ContentType.Application.Json
        "webmanifest" -> ContentType.parse("application/manifest+json")
        "txt" -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
        "xml" -> ContentType.Application.Xml
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "gif" -> ContentType.Image.GIF
        "webp" -> ContentType.parse("image/webp")
        "avif" -> ContentType.parse("image/avif")
        "ico" -> ContentType.parse("image/x-icon")
        "woff" -> ContentType.parse("font/woff")
        "woff2" -> ContentType.parse("font/woff2")
        "ttf" -> ContentType.parse("font/ttf")
        "otf" -> ContentType.parse("font/otf")
        "wasm" -> ContentType.parse("application/wasm")
        "pdf" -> ContentType.Application.Pdf
        else -> ContentType.Application.OctetStream
    }

    fun status(dataDir: File): QuillPenStatus {
        if (!desktop) return QuillPenStatus(platform = "web", available = false)
        val chosen = chosenFolder(dataDir)
        val detected = detectedFolder()
        val root = root(dataDir)
        return QuillPenStatus(
            platform = "desktop",
            available = root != null,
            folder = root?.path,
            chosen = chosen?.path,
            chosenValid = chosen?.let { isEmbedFolder(it) },
            detected = detected?.path,
            searched = standardLocations().map { it.path },
            canBrowse = folderChooser != null
        )
    }
}

@Serializable
data class QuillPenStatus(
    val platform: String,
    val available: Boolean,
    val folder: String? = null,
    val chosen: String? = null,
    val chosenValid: Boolean? = null,
    val detected: String? = null,
    val searched: List<String> = emptyList(),
    val canBrowse: Boolean = false
)

@Serializable
data class QuillPenFolderRequest(val folder: String? = null)

/**
 * `GET /api/quillpen` (status), `POST /api/quillpen/folder` (choose or clear the folder),
 * `POST /api/quillpen/browse` (native folder picker, desktop only) and the static files at
 * `/quillpen-embed/<path>`. The API needs a signed-in session; the static files do not (an
 * iframe cannot send the bearer token) and are only served by the desktop launcher.
 */
internal fun Routing.quillPenEmbedRoutes(sessions: Sessions, dataDir: File) {
    get("/api/quillpen") {
        call.handle { sessions.get(call.token()); QuillPenEmbed.status(dataDir) }
    }
    post("/api/quillpen/folder") {
        call.handle {
            sessions.get(call.token())
            require(QuillPenEmbed.desktop) { "Choosing a QuillPen folder is only possible in the desktop app." }
            val folder = call.receive<QuillPenFolderRequest>().folder?.trim()
            if (!folder.isNullOrEmpty()) {
                val f = File(folder)
                require(f.isAbsolute) { "Enter the full path of QuillPen's resources/embed folder." }
                require(QuillPenEmbed.isEmbedFolder(f)) { "No QuillPen editor (index.html) was found in $folder." }
            }
            QuillPenEmbed.setChosenFolder(dataDir, folder)
            QuillPenEmbed.status(dataDir)
        }
    }
    post("/api/quillpen/browse") {
        call.handle {
            sessions.get(call.token())
            val chooser = QuillPenEmbed.folderChooser
            require(QuillPenEmbed.desktop && chooser != null) { "No folder picker is available here; type the folder path instead." }
            QuillPenFolderRequest(chooser())
        }
    }
    get("/quillpen-embed") { call.respondRedirect("/quillpen-embed/") }
    get("/quillpen-embed/{path...}") {
        val root = if (QuillPenEmbed.desktop) QuillPenEmbed.root(dataDir) else null
        val file = root?.let { QuillPenEmbed.resolve(it, call.parameters.getAll("path").orEmpty()) }
        if (file == null) { call.respond(HttpStatusCode.NotFound, ApiError("Not found")); return@get }
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.respondBytes(bytes, QuillPenEmbed.contentType(file.name))
    }
}

