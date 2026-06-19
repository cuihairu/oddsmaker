// swift-tools-version:5.7
import PackageDescription

let package = Package(
    name: "Oddsmaker",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "Oddsmaker", targets: ["Oddsmaker"])
    ],
    targets: [
        .target(name: "Oddsmaker", path: "Sources/Oddsmaker"),
        .testTarget(name: "OddsmakerTests", dependencies: ["Oddsmaker"], path: "Tests/OddsmakerTests")
    ]
)
