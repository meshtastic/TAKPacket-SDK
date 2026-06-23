// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MeshtasticTAK",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(
            name: "MeshtasticTAK",
            targets: ["MeshtasticTAK"]
        ),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.38.1"),
        // DocC documentation generator (command plugin only; not linked into the
        // product). Enables `swift package generate-documentation`.
        .package(url: "https://github.com/apple/swift-docc-plugin", from: "1.5.0"),
    ],
    targets: [
        .target(
            name: "CZstd",
            path: "Sources/CZstd",
            publicHeadersPath: "include",
            cSettings: [
                .define("ZSTD_DISABLE_ASM"),
            ]
        ),
        .target(
            name: "MeshtasticTAK",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
                "CZstd",
            ],
            resources: [
                .copy("Resources/dict_non_aircraft.zstd"),
                .copy("Resources/dict_aircraft.zstd"),
            ]
        ),
        .testTarget(
            name: "MeshtasticTAKTests",
            dependencies: ["MeshtasticTAK"],
            resources: [
                .copy("Fixtures"),
            ]
        ),
    ]
)
