plugins {
    kotlin("jvm") version "1.9.22"
    id("com.google.protobuf") version "0.9.4"
}

group = "org.meshtastic"
version = "0.1.0"

repositories {
    mavenCentral()
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}

// Copy only atak.proto from the protobufs submodule into a staging dir for protoc
val copyProto = tasks.register<Copy>("copyAtakProto") {
    from("../protobufs/meshtastic/atak.proto")
    into(layout.buildDirectory.dir("proto-staging/meshtastic"))
}

tasks.named("extractProto") { dependsOn(copyProto) }

sourceSets {
    main {
        proto {
            srcDir(layout.buildDirectory.dir("proto-staging"))
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")
    implementation("com.github.luben:zstd-jni:1.5.6-4")
    implementation("org.ogce:xpp3:1.1.6")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
