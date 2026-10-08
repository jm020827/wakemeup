import SwiftUI
import UIKit

@MainActor final class PhoneAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        AppRuntime.shared.launch(); return true
    }
}
@main struct WakeMeUpApp: App {
    @UIApplicationDelegateAdaptor(PhoneAppDelegate.self) private var delegate
    @StateObject private var model = AppRuntime.shared.model
    var body: some Scene { WindowGroup { RootView(model: model).tint(Palette.accent) } }
}
