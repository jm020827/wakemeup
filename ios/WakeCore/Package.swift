// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "WakeCore",
    platforms: [.iOS("26.0"), .watchOS("26.0"), .macOS(.v13)],
    products: [.library(name: "WakeCore", targets: ["WakeCore"])],
    targets: [.target(name: "WakeCore"), .testTarget(name: "WakeCoreTests", dependencies: ["WakeCore"]) ]
)
