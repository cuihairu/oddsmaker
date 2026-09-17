// swift-tools-version:5.7
import PackageDescription

#if os(Linux)
// Linux 无 CryptoKit,用 apple/swift-crypto(其 Insecure.SHA1 API 与 CryptoKit 完全一致);
// Apple 平台走系统 CryptoKit,不引入第三方依赖,iOS 产物不变。
let packageDependencies: [Package.Dependency] = [
    .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0")
]
let oddsmakerDependencies: [Target.Dependency] = [
    .product(name: "Crypto", package: "swift-crypto")
]
#else
let packageDependencies: [Package.Dependency] = []
let oddsmakerDependencies: [Target.Dependency] = []
#endif

let package = Package(
    name: "Oddsmaker",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "Oddsmaker", targets: ["Oddsmaker"])
    ],
    dependencies: packageDependencies,
    targets: [
        .target(name: "Oddsmaker", dependencies: oddsmakerDependencies, path: "Sources/Oddsmaker"),
        .testTarget(name: "OddsmakerTests", dependencies: ["Oddsmaker"], path: "Tests/OddsmakerTests")
    ]
)
