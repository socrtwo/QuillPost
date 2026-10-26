import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Load signing properties from local.properties (never committed) or environment
// variables. Nothing here is hardcoded — if no keystore is configured the release
// signingConfig is simply left unset and assembleRelease will report it.
val keystorePropertiesFile = rootProject.file("local.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

android {
    namespace = "info.socrtwo.quillpost"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.socrtwo.quillpost"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // The Android app is the web client packaged as an app: the very same Ktor backend and
    // Outlook-style UI as ../web are compiled into the APK, started on 127.0.0.1 inside the
    // app process and shown full-screen in a WebView. One code base, identical features.
    sourceSets {
        getByName("main") {
            java.srcDir("../web/src/main/kotlin")
            resources.srcDir("../web/src/main/resources")
        }
    }

    signingConfigs {
        // Release signing reads from local.properties or env vars. Keys:
        //   RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
        // (env equivalents: QUILLPOST_RELEASE_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD)
        val storeFilePath = signingValue("RELEASE_STORE_FILE", "QUILLPOST_RELEASE_STORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = signingValue("RELEASE_STORE_PASSWORD", "QUILLPOST_RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS", "QUILLPOST_RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD", "QUILLPOST_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signingConfig when a keystore is actually configured.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // Jakarta/Angus Mail, Ktor and kotlinx ship duplicate metadata across their jars;
            // keep one copy of each so the mail providers still resolve on Android.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/previous-compilation-data.bin",
                "META-INF/*.kotlin_module",
                // The organisation table is compiled into BrandKnowledgeBaseExtra.kt; the source
                // TSVs are only needed by scripts/gen-brands.py.
                "brands/**"
            )
            pickFirsts += setOf(
                "META-INF/javamail.default.providers",
                "META-INF/javamail.default.address.map",
                "META-INF/javamail.providers",
                "META-INF/javamail.address.map",
                "META-INF/mailcap",
                "META-INF/mailcap.default",
                "META-INF/mimetypes.default"
            )
        }
    }
}

val ktor = "3.0.3"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")

    // The embedded backend: Ktor's coroutine-based CIO engine (no Netty on Android).
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-cio-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Ktor logs through SLF4J; the simple binding prints to logcat via System.err.
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // Jakarta Mail (IMAP / POP3 / SMTP) — Eclipse Angus implementation. This transitively
    // brings angus-activation + jakarta.activation-api (DataHandler etc.), so no separate
    // activation dependency is declared (doing so would duplicate those classes).
    implementation("org.eclipse.angus:angus-mail:2.0.3")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
