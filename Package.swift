// swift-tools-version: 5.9
// Root-level Package.swift for SPM consumption via GitHub URL.
// The actual Swift implementation lives in swift/.

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
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.25.0"),
    ],
    targets: [
        .target(
            name: "CZstd",
            path: "swift/Sources/CZstd",
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
            path: "swift/Sources/MeshtasticTAK",
            resources: [
                .copy("Resources/dict_non_aircraft.zstd"),
                .copy("Resources/dict_aircraft.zstd"),
            ]
        ),
        .testTarget(
            name: "MeshtasticTAKTests",
            dependencies: ["MeshtasticTAK"],
            path: "swift/Tests/MeshtasticTAKTests"
            // Fixtures are loaded directly from `testdata/` at the repo root at
            // test runtime using `#filePath` to build the path. This matches
            // what the Kotlin, Python, C#, and TypeScript suites do, and means
            // adding a new fixture is a single-step operation (drop the .xml
            // into `testdata/cot_xml/` and re-run `./build.sh test`) instead
            // of also having to mirror it under `Fixtures/` for SPM to bundle.
        ),
    ]
)
