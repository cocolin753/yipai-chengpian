// swift-tools-version: 5.9
// 精剪算法核心（与安卓 core/ 目录逻辑一致），不依赖 UIKit，可在 Linux/macOS 上跑单元测试
import PackageDescription

let package = Package(
    name: "YiPaiCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "YiPaiCore", targets: ["YiPaiCore"])],
    targets: [
        .target(name: "YiPaiCore"),
        .testTarget(name: "YiPaiCoreTests", dependencies: ["YiPaiCore"]),
    ]
)
