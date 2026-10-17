package info.socrtwo.quillpost.web

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Entry point of the stand-alone server distribution (`bin/quillpost-web`). It lives outside
 * `src/main` so the Android app can compile the backend without pulling in Netty.
 *
 * Environment: `PORT` (default 8080), `HOST` (default 0.0.0.0 — bind to every interface so
 * phones and tablets on the same network, including the iOS app, can reach it),
 * `QUILLPOST_DATA_DIR` (default `~/.quillpost`).
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    embeddedServer(Netty, port = port, host = host, module = Application::module).start(wait = true)
}
