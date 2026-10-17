import SwiftUI
import WebKit
import UIKit

/// Full-screen WebKit view for the Outlook-style QuillPost client.
///
/// Native glue, mirroring the Android shell:
/// - `mailto:` and links to other sites open outside the page (in-app compose / Safari);
/// - attachment downloads (Content-Disposition: attachment) are saved and offered in the share sheet;
/// - `window.open` pop-ups are routed back into the page (app.js shows in-app dialogs instead);
/// - the page can ask for printing and for the "change server" screen via `webkit.messageHandlers.quillpost`;
/// - the file picker for compose attachments is WebKit's own.
struct QuillPostWebView: UIViewRepresentable {
    let state: AppState
    let url: URL
    let reloadToken: Int
    /// Number of opened files waiting; a change makes SwiftUI call updateUIView, which delivers them.
    let pendingFiles: Int

    func makeCoordinator() -> Coordinator { Coordinator(state: state) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()          // keeps localStorage (remembered account) across launches
        config.allowsInlineMediaPlayback = true
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.userContentController.add(context.coordinator, name: "quillpost")
        config.applicationNameForUserAgent = "QuillPostApp/\(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0") (iOS)"

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = false
        web.scrollView.bounces = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.isOpaque = false
        web.backgroundColor = .clear
        context.coordinator.webView = web
        context.coordinator.load(url)
        context.coordinator.lastReloadToken = reloadToken
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        if context.coordinator.lastReloadToken != reloadToken || context.coordinator.loadedURL != url {
            context.coordinator.lastReloadToken = reloadToken
            context.coordinator.load(url)
        }
        context.coordinator.deliverFiles()
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: "quillpost")
    }

    // MARK: - Coordinator

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, WKDownloadDelegate {
        let state: AppState
        weak var webView: WKWebView?
        var loadedURL: URL?
        var lastReloadToken = -1
        private var pageLoaded = false
        private var downloadFiles: [ObjectIdentifier: URL] = [:]

        init(state: AppState) { self.state = state }

        /// Hands opened mail files to the page (window.quillpostOpenData), which uploads them to the server.
        func deliverFiles() {
            guard pageLoaded, let web = webView else { return }
            Task { @MainActor in
                let files = state.pendingFiles
                if files.isEmpty { return }
                state.pendingFiles = []
                for url in files {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { continue }
                    let js = "window.quillpostOpenData && window.quillpostOpenData(\(Coordinator.jsString(url.lastPathComponent)), \"\(data.base64EncodedString())\")"
                    web.evaluateJavaScript(js, completionHandler: nil)
                }
            }
        }

        func load(_ url: URL) {
            pageLoaded = false
            loadedURL = url
            webView?.load(URLRequest(url: url))
        }

        private func isServer(_ u: URL?) -> Bool {
            guard let u, let base = loadedURL else { return false }
            return u.scheme?.lowercased() == base.scheme?.lowercased() && u.host?.lowercased() == base.host?.lowercased() && (u.port ?? defaultPort(u)) == (base.port ?? defaultPort(base))
        }
        private func defaultPort(_ u: URL) -> Int { u.scheme?.lowercased() == "https" ? 443 : 80 }

        // MARK: navigation

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let u = navigationAction.request.url else { decisionHandler(.cancel); return }
            if u.scheme?.lowercased() == "mailto" {
                let js = "window.quillpostMailto && window.quillpostMailto(\(Coordinator.jsString(u.absoluteString)))"
                webView.evaluateJavaScript(js, completionHandler: nil)
                decisionHandler(.cancel); return
            }
            if isServer(u) || u.absoluteString == "about:blank" || u.scheme == "blob" || u.scheme == "data" {
                decisionHandler(.allow); return
            }
            // Anything else — links inside messages, provider help pages — leaves the app.
            UIApplication.shared.open(u)
            decisionHandler(.cancel)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if let http = navigationResponse.response as? HTTPURLResponse {
                let disposition = (http.value(forHTTPHeaderField: "Content-Disposition") ?? "").lowercased()
                if disposition.hasPrefix("attachment") || !navigationResponse.canShowMIMEType {
                    decisionHandler(.download); return
                }
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
            download.delegate = self
        }
        func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
            download.delegate = self
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor in
                state.loadError = nil
                // Let the page's own splash fade before revealing the web view.
                try? await Task.sleep(nanoseconds: 350_000_000)
                withAnimation(.easeOut(duration: 0.35)) { state.webReady = true }
                pageLoaded = true
                deliverFiles()
            }
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            let e = error as NSError
            if e.domain == NSURLErrorDomain && e.code == NSURLErrorCancelled { return }
            Task { @MainActor in state.loadError = error.localizedDescription; state.webReady = true }
        }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            let e = error as NSError
            if e.domain == NSURLErrorDomain && e.code == NSURLErrorCancelled { return }
            Task { @MainActor in state.loadError = error.localizedDescription; state.webReady = true }
        }

        // MARK: pop-ups (target=_blank) → Safari; window.open("") → handled in-page

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let u = navigationAction.request.url, !u.absoluteString.isEmpty, u.absoluteString != "about:blank" {
                if isServer(u) { webView.load(URLRequest(url: u)) } else { UIApplication.shared.open(u) }
            }
            return nil
        }

        func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
            present(UIAlertController(title: nil, message: message, preferredStyle: .alert), actions: [UIAlertAction(title: "OK", style: .default) { _ in completionHandler() }])
        }
        func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
            present(UIAlertController(title: nil, message: message, preferredStyle: .alert), actions: [
                UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) },
                UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) }])
        }
        func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
            let alert = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)
            alert.addTextField { $0.text = defaultText }
            present(alert, actions: [
                UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(nil) },
                UIAlertAction(title: "OK", style: .default) { _ in completionHandler(alert.textFields?.first?.text) }])
        }

        // MARK: messages from app.js

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
            switch type {
            case "open":
                if let s = body["url"] as? String, let u = URL(string: s) { UIApplication.shared.open(u) }
            case "print":
                printPage(title: body["title"] as? String ?? "QuillPost message")
            case "changeServer":
                Task { @MainActor in state.changeServer() }
            default:
                break
            }
        }

        private func printPage(title: String) {
            guard let web = webView else { return }
            let info = UIPrintInfo(dictionary: nil)
            info.jobName = title
            info.outputType = .general
            let controller = UIPrintInteractionController.shared
            controller.printInfo = info
            controller.printFormatter = web.viewPrintFormatter()
            controller.present(animated: true, completionHandler: nil)
        }

        // MARK: downloads → share sheet

        func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String, completionHandler: @escaping (URL?) -> Void) {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("quillpost-downloads", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let safe = suggestedFilename.replacingOccurrences(of: "/", with: "_")
            let target = dir.appendingPathComponent(safe.isEmpty ? "attachment" : safe)
            try? FileManager.default.removeItem(at: target)
            downloadFiles[ObjectIdentifier(download)] = target
            completionHandler(target)
        }

        func downloadDidFinish(_ download: WKDownload) {
            guard let file = downloadFiles.removeValue(forKey: ObjectIdentifier(download)) else { return }
            let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            if let pop = sheet.popoverPresentationController, let web = webView {
                pop.sourceView = web
                pop.sourceRect = CGRect(x: web.bounds.midX, y: web.bounds.maxY - 60, width: 1, height: 1)
            }
            topController()?.present(sheet, animated: true)
        }

        func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
            downloadFiles.removeValue(forKey: ObjectIdentifier(download))
            present(UIAlertController(title: "Download failed", message: error.localizedDescription, preferredStyle: .alert), actions: [UIAlertAction(title: "OK", style: .default)])
        }

        // MARK: helpers

        private func present(_ alert: UIAlertController, actions: [UIAlertAction]) {
            actions.forEach(alert.addAction)
            topController()?.present(alert, animated: true)
        }

        private func topController() -> UIViewController? {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            guard let window = scenes.flatMap({ $0.windows }).first(where: { $0.isKeyWindow }) ?? scenes.first?.windows.first else { return nil }
            var top = window.rootViewController
            while let presented = top?.presentedViewController { top = presented }
            return top
        }

        static func jsString(_ s: String) -> String {
            let escaped = s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"").replacingOccurrences(of: "\n", with: "\\n")
            return "\"\(escaped)\""
        }
    }
}
