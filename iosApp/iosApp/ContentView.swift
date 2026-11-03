import SwiftUI
import UIKit
import Shared

/// Hosts the shared Compose Multiplatform UI.
/// iOS-only pieces (WidgetKit widgets, App Intents for the Shortcuts SMS automation,
/// Vision OCR) are written in Swift next to this file and call into `Shared`.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
    }
}
