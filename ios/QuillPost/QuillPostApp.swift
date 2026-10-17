import SwiftUI

/// QuillPost for iPhone and iPad.
///
/// Jakarta Mail (the IMAP/SMTP engine) is JVM-only, so the iOS app cannot run the backend
/// itself. Instead it shows the very same Outlook-style web client — served by a QuillPost
/// server you run at home, on a Raspberry Pi, a NAS or a VPS (`quillpost-web` zip or the
/// desktop app) — full-screen in a WebKit view, with printing, attachment saving, external
/// links and mailto: handled natively. Every feature of the web/desktop version is present.
@main
struct QuillPostApp: App {
    @StateObject private var state = AppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(state)
                // "Open in QuillPost" / Files app: mail files are handed to the page, which uploads them.
                .onOpenURL { url in if url.isFileURL { state.pendingFiles.append(url) } }
        }
    }
}

@MainActor
final class AppState: ObservableObject {
    /// Base URL of the QuillPost server, e.g. `http://192.168.1.20:8080`. Persisted across launches.
    @Published var serverURL: String {
        didSet { UserDefaults.standard.set(serverURL, forKey: "serverURL") }
    }
    /// True while the user is on the "which server?" screen.
    @Published var choosingServer: Bool
    /// Set when the page could not be loaded; shown with a retry button.
    @Published var loadError: String? = nil
    /// Incremented to force the web view to reload.
    @Published var reloadToken: Int = 0
    /// True once the web client has rendered; the in-app splash fades out then.
    @Published var webReady: Bool = false
    /// Mail files opened with QuillPost (.eml, .msg, .mbox, .pst…), waiting for the page to take them.
    @Published var pendingFiles: [URL] = []

    init() {
        let saved = UserDefaults.standard.string(forKey: "serverURL") ?? ""
        serverURL = saved
        choosingServer = saved.isEmpty
    }

    var serverBase: URL? {
        var s = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "http://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        return URL(string: s + "/")
    }

    func useServer(_ url: String) {
        serverURL = url
        loadError = nil
        webReady = false
        choosingServer = false
        reloadToken += 1
    }

    func changeServer() {
        choosingServer = true
    }

    func retry() {
        loadError = nil
        reloadToken += 1
    }
}
