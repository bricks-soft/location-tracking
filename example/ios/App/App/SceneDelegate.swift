import UIKit
import Capacitor
import WebKit

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }

        window = UIWindow(windowScene: windowScene)
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
        if ProcessInfo.processInfo.arguments.contains("--ios-stage1-smoke") || permissionTest {
            configuration.userContentController.add(self, name: "stage1")
            configuration.userContentController.addUserScript(WKUserScript(source: "window.addEventListener('error', function(e) { window.webkit.messageHandlers.stage1.postMessage({success:false,error:String(e.message)}); }); localStorage.setItem('lt.e2e', '1'); window.webkit.messageHandlers.stage1.postMessage({phase:'document-start'});", injectionTime: .atDocumentStart, forMainFrameOnly: true))
            let script = permissionTest ? "ios-permission-smoke.js" : "ios-smoke.js"
            configuration.userContentController.addUserScript(WKUserScript(source: "var s = document.createElement('script'); s.src = '\(script)'; s.onerror = function() { window.webkit.messageHandlers.stage1.postMessage({success:false,error:'Could not load smoke script'}); }; document.body.appendChild(s);", injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "stage1", let body = message.body as? [String: Any] else { return }
        do {
            let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let data = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
            try data.write(to: directory.appendingPathComponent(body["success"] == nil ? "ios-stage1-state.json" : "ios-stage1-smoke.json"), options: .atomic)
        } catch { assertionFailure("Could not save simulator smoke results") }
    }
}
