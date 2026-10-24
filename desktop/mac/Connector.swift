import Cocoa
import WebKit
import UniformTypeIdentifiers

final class ConnectorApp: NSObject, NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    var window: NSWindow!
    var web: WKWebView!
    var backend: Process?
    var output = Data()
    var origin: URL?
    var quitting = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        let menu = NSMenu()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "退出 Codex Pocket", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let item = NSMenuItem(); item.submenu = appMenu; menu.addItem(item)
        let edit = NSMenuItem(); let editMenu = NSMenu(title: "编辑")
        for (title, action, key) in [("复制", "copy:", "c"), ("粘贴", "paste:", "v"), ("剪切", "cut:", "x"), ("全选", "selectAll:", "a")] { editMenu.addItem(withTitle: title, action: Selector(action), keyEquivalent: key) }
        edit.submenu = editMenu; menu.addItem(edit); NSApp.mainMenu = menu
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 800), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Codex Pocket"; window.minSize = NSSize(width: 720, height: 620); window.center(); window.delegate = self; window.isReleasedWhenClosed = false
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.add(self, name: "pocket")
        web = WKWebView(frame: .zero, configuration: configuration); web.navigationDelegate = self; web.uiDelegate = self
        window.contentView = web; window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        startBackend()
    }
    func showError(_ text: String) {
        let safe = text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
        web.loadHTMLString("<meta charset='utf-8'><style>body{font:16px -apple-system;background:#f6f8fc;color:#182332;padding:80px}button{background:#1677ff;color:white;border:0;border-radius:12px;padding:14px 24px;font-size:16px}</style><h1>Codex Pocket</h1><p>\(safe)</p><button onclick=\"window.webkit.messageHandlers.pocket.postMessage('retry')\">重新尝试</button>", baseURL: nil)
    }
    func startBackend() {
        if backend?.isRunning == true { return }
        showError("正在启动连接器…")
        guard let resources = Bundle.main.resourceURL else { showError("应用文件不完整，请重新解压安装包。"); return }
        let root = resources.appendingPathComponent("connector").path
        let process = Process(); process.executableURL = URL(fileURLWithPath: "/bin/zsh")
        process.arguments = ["-c", "set -e; cd \"$1\"; source scripts/find-codex-node.sh; exec \"$POCKET_NODE\" connector.mjs --native", "--", root]
        var env = ProcessInfo.processInfo.environment
        for key in ["CODEX_THREAD_ID", "CODEX_DESKTOP_CONTEXT_THREAD_ID", "CODEX_APP_TOOLS_PIPE_PATH"] { env.removeValue(forKey: key) }
        process.environment = env
        let pipe = Pipe(); process.standardOutput = pipe
        let state = env["POCKET_STATE_DIR"] ?? (NSHomeDirectory() + "/Library/Application Support/Codex Pocket")
        try? FileManager.default.createDirectory(atPath: state, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let logPath = state + "/native-connector.log"
        if !FileManager.default.fileExists(atPath: logPath) { FileManager.default.createFile(atPath: logPath, contents: nil, attributes: [.posixPermissions: 0o600]) }
        let log = FileHandle(forWritingAtPath: logPath); log?.seekToEndOfFile(); process.standardError = log ?? FileHandle.nullDevice
        output = Data(); origin = nil; backend = process
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            if data.isEmpty { handle.readabilityHandler = nil; return }
            DispatchQueue.main.async {
                guard let self = self, self.backend === process else { return }
                self.output.append(data)
                if self.output.count > 65536 { self.output.removeAll(); return }
                while let range = self.output.range(of: Data([10])) {
                    let line = self.output.subdata(in: 0..<range.lowerBound); self.output.removeSubrange(0...range.lowerBound)
                    if let value = try? JSONSerialization.jsonObject(with: line) as? [String: Any], value["event"] as? String == "ready", let raw = value["url"] as? String, let url = URL(string: raw), url.host == "127.0.0.1" {
                        self.origin = url; self.web.load(URLRequest(url: url))
                    }
                }
            }
        }
        process.terminationHandler = { [weak self] _ in DispatchQueue.main.async {
            guard let self = self, !self.quitting, self.backend === process else { return }
            self.showError("连接服务已退出。请确认 Codex 已安装并登录，然后重试。详细原因保存在软件日志中。")
        } }
        do { try process.run() } catch { showError("无法启动 Codex 运行环境，请重新安装连接器。"); return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
            guard let self = self, self.backend === process, self.origin == nil, process.isRunning else { return }
            self.showError("启动超时，请退出后重试，或检查本机 Codex 是否已正确安装。")
        }
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        if message.body as? String == "retry" { startBackend() }
        if let data = message.body as? [String: String], data["action"] == "saveDiagnostics", let text = data["text"] {
            let panel = NSSavePanel(); panel.nameFieldStringValue = "Codex-Pocket-诊断报告.json"
            panel.beginSheetModal(for: window) { response in if response == .OK, let url = panel.url { try? text.write(to: url, atomically: true, encoding: .utf8) } }
        }
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
        if url.scheme == "about" || (url.scheme == "http" && url.host == "127.0.0.1" && url.port == origin?.port) { decisionHandler(.allow); return }
        decisionHandler(.cancel)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { if (error as NSError).code != NSURLErrorCancelled { showError("窗口加载失败，请重新打开软件。"); } }
    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel(); panel.canChooseFiles = true; panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        panel.allowedContentTypes = ["pem", "crt", "cer"].compactMap { UTType(filenameExtension: $0) }
        panel.beginSheetModal(for: window) { response in completionHandler(response == .OK ? panel.urls : nil) }
    }
    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = NSAlert(); alert.messageText = "Codex Pocket"; alert.informativeText = message; alert.addButton(withTitle: "继续"); alert.addButton(withTitle: "取消")
        alert.beginSheetModal(for: window) { result in completionHandler(result == .alertFirstButtonReturn) }
    }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true); return true }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { return true }
    func applicationWillTerminate(_ notification: Notification) { quitting = true; if backend?.isRunning == true { backend?.terminate() } }
}

let application = NSApplication.shared
let delegate = ConnectorApp()
application.delegate = delegate
application.run()
