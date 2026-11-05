import UIKit
import Capacitor
import WebKit

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        window = UIWindow(windowScene: windowScene)
        window?.frame = windowScene.coordinateSpace.bounds
        window?.rootViewController = ExampleViewController()
        window?.makeKeyAndVisible()

        SceneDelegateProxy.shared.scene(scene, willConnectTo: session, options: connectionOptions)
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        SceneDelegateProxy.shared.scene(scene, openURLContexts: URLContexts)
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        SceneDelegateProxy.shared.scene(scene, continue: userActivity)
    }
}


final class ExampleViewController: CAPBridgeViewController, WKScriptMessageHandler {
    override func capacitorDidLoad() {
        super.capacitorDidLoad()
        guard let configuration = webView?.configuration else { return }
        let permissionTest = ProcessInfo.processInfo.arguments.contains("--ios-permission-smoke")
        if ProcessInfo.processInfo.arguments.contains("--e2e-web-smoke") || permissionTest {
            configuration.userContentController.add(self, name: "e2e")
            configuration.userContentController.addUserScript(WKUserScript(source: "window.addEventListener('error', function(e) { window.webkit.messageHandlers.e2e.postMessage({success:false,error:String(e.message)}); }); localStorage.setItem('lt.e2e', '1'); window.webkit.messageHandlers.e2e.postMessage({phase:'document-start'});", injectionTime: .atDocumentStart, forMainFrameOnly: true))
            let script = permissionTest ? "ios-permission-smoke.js" : "e2e-web-smoke.js"
            configuration.userContentController.addUserScript(WKUserScript(source: "var s = document.createElement('script'); s.src = '\(script)'; s.onerror = function() { window.webkit.messageHandlers.e2e.postMessage({success:false,error:'Could not load smoke script'}); }; document.body.appendChild(s);", injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "e2e", let body = message.body as? [String: Any] else { return }
        if body["success"] as? Bool == true, let webView {
            webView.takeSnapshot(with: nil) { image, error in
                var report = body
                report["view"] = ["width": Double(webView.bounds.width), "height": Double(webView.bounds.height), "windowWidth": Double(webView.window?.bounds.width ?? 0), "windowHeight": Double(webView.window?.bounds.height ?? 0), "visible": webView.window?.isHidden == false]
                do {
                    guard let image, let data = image.pngData() else { throw error ?? NSError(domain: "ExampleSnapshot", code: 1) }
                    let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                    try data.write(to: directory.appendingPathComponent("e2e-web-smoke.png"), options: .atomic)
                } catch { report["success"] = false; report["error"] = "WebView snapshot failed: " + error.localizedDescription }
                self.writeReport(report)
            }
        } else { writeReport(body) }
    }
    private func writeReport(_ body: [String: Any]) {
        do {
            let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let data = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
            try data.write(to: directory.appendingPathComponent(body["success"] == nil ? "e2e-web-state.json" : "e2e-web-smoke.json"), options: .atomic)
        } catch { assertionFailure("Could not save simulator smoke results") }
    }
}
