plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "info.socrtwo.quillpost"
version = "1.5.0"

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
// spoofed and blacklisted messages) and then Anne's Post itself, so the UI can be tried without
// touching a real mailbox. Sign in with demo@quillpost.test / demo.
tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run Anne's Post against an in-memory demo mailbox (demo@quillpost.test / demo)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("info.socrtwo.quillpost.web.demo.DemoServerKt")
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
    mainClass.set("info.socrtwo.quillpost.web.ApplicationKt")
}

// The web UI's AI modules (src/main/resources/web/ai) are plain JavaScript with Node unit tests
// in src/test/js. `check` runs them with `node --test` when Node.js (18+) is on the PATH — no npm,
// no packages. Without Node the task is skipped with a warning. Run alone: ./gradlew jsTest
val jsTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the web UI's JavaScript unit tests with node --test (no npm needed)."
    val nodeOnPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { File(it, "node").canExecute() || File(it, "node.exe").canExecute() }
    onlyIf { nodeOnPath.also { if (!it) logger.warn("jsTest skipped: Node.js is not on the PATH") } }
    workingDir = projectDir
    commandLine(listOf("node", "--test") + fileTree("src/test/js") { include("*.test.js") }.files.map { it.relativeTo(projectDir).path }.sorted())
    inputs.dir("src/test/js")
    inputs.dir("src/main/resources/web/ai")
    outputs.upToDateWhen { false }
}
tasks.named("check") { dependsOn(jsTest) }
