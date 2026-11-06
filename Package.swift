// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BricksSoftCapacitorLocationTracking",
    platforms: [.iOS(.v15)],
    products: [.library(name: "BricksSoftCapacitorLocationTracking", targets: ["LocationTrackingPlugin"])],
    dependencies: [.package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")],
    targets: [
        .target(name: "LocationTrackingPlugin", dependencies: [.product(name: "Capacitor", package: "capacitor-swift-pm"), .product(name: "Cordova", package: "capacitor-swift-pm")], path: "ios/Sources/LocationTrackingPlugin", resources: [.copy("PrivacyInfo.xcprivacy")], linkerSettings: [.linkedLibrary("sqlite3")]),
        .testTarget(name: "LocationTrackingPluginTests", dependencies: ["LocationTrackingPlugin"], path: "ios/Tests/LocationTrackingPluginTests")
    ]
)
