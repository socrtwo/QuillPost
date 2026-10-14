plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "info.socrtwo.quillbox"
version = "1.4.0"

repositories {
    mavenCentral()
}

// src/main holds the portable backend (shared with the desktop launcher and the Android app,
// which compile it from here); src/jvmMain holds the Netty entry point of the server zip.
sourceSets {
    main {
        kotlin.srcDir("src/jvmMain/kotlin")
    }
}

val ktor = "3.0.3"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Jakarta (Angus) Mail — the backend speaks IMAP/POP3/SMTP on behalf of the browser.
    implementation("org.eclipse.angus:angus-mail:2.0.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // In-memory IMAP/SMTP server used by the demo mailbox and integration tests.
    testImplementation("com.icegreen:greenmail:2.1.0")
}

tasks.test {
    useJUnitPlatform()
}

// `./gradlew demo` starts a throw-away IMAP/SMTP server seeded with sample mail (including
// spoofed and blacklisted messages) and then Quillbox itself, so the UI can be tried without
// touching a real mailbox. Sign in with demo@quillbox.test / demo.
tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run Quillbox against an in-memory demo mailbox (demo@quillbox.test / demo)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("info.socrtwo.quillbox.web.demo.DemoServerKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("info.socrtwo.quillbox.web.ApplicationKt")
}
