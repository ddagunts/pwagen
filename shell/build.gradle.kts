// AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is neither
// needed nor accepted here.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.pwagen.shell"

    // android-36.1 is the platform installed locally; the minor level must be
    // stated explicitly or AGP looks for a plain android-36 that is not present.
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.pwagen.shell"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true

            // Resource shrinking is deliberately OFF. It rewrites res/ paths to
            // obfuscated names like res/9w.png, which would leave the generator
            // guessing which file is the launcher icon. Keeping real paths also
            // makes the template APK auditable by hand. The shell has five PNGs
            // and one theme, so there is nothing meaningful to shrink.
            isShrinkResources = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Deliberately no signingConfig: AGP emits this APK unsigned and
            // pwagen signs it on device with the hardware-backed key.
            signingConfig = null
        }
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources {
            // Only needed for Kotlin reflection, which the shell does not use.
            // Drops roughly 57 KB from every generated APK.
            excludes += "kotlin/**"
            excludes += "META-INF/*.version"
            excludes += "META-INF/version-control-info.textproto"
            excludes += "META-INF/com/android/build/gradle/app-metadata.properties"
        }
    }

    // Kotlin's jvmTarget follows targetCompatibility under built-in Kotlin.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kept deliberately spare: this is the network-facing module, so it depends on
// the shared config schema and nothing else.
dependencies {
    implementation(project(":config"))
    implementation(libs.kotlinx.serialization.json)
}

// ---------------------------------------------------------------------------
// Template APK publication
//
// :app embeds this module's unsigned release APK as an asset and rewrites a
// copy of it per PWA. Publishing it through a configuration rather than having
// :app reach into shell/build/ keeps the two modules properly decoupled.
// ---------------------------------------------------------------------------

val templateApkUsage = "pwagen-template-apk"

val templateApkElements = configurations.create("templateApkElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, templateApkUsage))
    }
}

val templateApk = tasks.register<Sync>("templateApk") {
    description = "Stages the unsigned release APK under a stable name for :app to embed."
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*-unsigned.apk")
        rename { "shell-template.apk" }
    }
    into(layout.buildDirectory.dir("templateApk"))
}

artifacts {
    add(templateApkElements.name, layout.buildDirectory.dir("templateApk")) {
        builtBy(templateApk)
    }
}
