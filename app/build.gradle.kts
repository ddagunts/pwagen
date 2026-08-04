import com.android.build.api.variant.AndroidComponentsExtension
import java.util.Properties

// AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is neither
// needed nor accepted here. The Compose compiler plugin is still applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Copies the :shell template APK into the generator's assets.
 *
 * The template is never checked in: it is built from :shell in the same build,
 * so the shell runtime and the generator that embeds it cannot drift apart.
 */
abstract class EmbedTemplateApk : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templateApk: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun embed() {
        val destination = outputDirectory.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()

        val apk = templateApk.asFileTree.files.single { it.name.endsWith(".apk") }
        apk.copyTo(destination.resolve("shell-template.apk"), overwrite = true)
    }
}

val templateApkConfiguration = configurations.create("templateApk") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "pwagen-template-apk"))
    }
}

val embedTemplateApk = tasks.register<EmbedTemplateApk>("embedTemplateApk") {
    templateApk.from(templateApkConfiguration)
    outputDirectory.set(layout.buildDirectory.dir("generated/templateAsset"))
}

extensions.configure<AndroidComponentsExtension<*, *, *>>("androidComponents") {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            embedTemplateApk,
            EmbedTemplateApk::outputDirectory,
        )
    }
}

// The generation pipeline is pure Java, so it runs end-to-end under plain JVM
// unit tests. They verify their output with the same aapt2/apksigner/zipalign
// that Android ships, which catches the packaging mistakes that would otherwise
// only surface as an install failure on a real phone.
/** Located the same way AGP finds it, so the tests need no extra configuration. */
val androidSdkDirectory: File = run {
    val fromProperties = File(rootDir, "local.properties")
        .takeIf { it.exists() }
        ?.let { file ->
            Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir")
        }
    val path = fromProperties
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME")
    File(path)
}

val buildToolsPath = androidSdkDirectory.resolve("build-tools/36.1.0").absolutePath

// Resolved to plain strings at configuration time: a lambda here would capture
// the build script itself, which the configuration cache cannot serialize.
val templateApkPath: String = layout.buildDirectory
    .file("generated/templateAsset/shell-template.apk")
    .get().asFile.absolutePath

tasks.withType<Test>().configureEach {
    dependsOn(embedTemplateApk)
    systemProperty("pwagen.template.apk", templateApkPath)
    systemProperty("pwagen.buildTools", buildToolsPath)
}

android {
    namespace = "dev.pwagen.app"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.pwagen.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    // A local development key, gitignored, so test builds install and can be
    // updated in place. Real releases are signed separately; this is not it.
    val developmentKeystore = rootProject.file("keystore/pwagen-dev.jks")

    // The published signing key. Both the properties file and the keystore it
    // points at are gitignored, so this is absent on a fresh clone and the
    // build falls back to the development key rather than failing.
    val releaseSigning = rootProject.file("keystore.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
        ?.takeIf { properties ->
            rootProject.file(properties.getProperty("storeFile") ?: "").exists()
        }

    signingConfigs {
        if (developmentKeystore.exists()) {
            create("development") {
                storeFile = developmentKeystore
                storePassword = "pwagenpwagen"
                keyAlias = "pwagen"
                keyPassword = "pwagenpwagen"
            }
        }
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                // v1 is pointless at minSdk 30 and only adds a forgeable
                // JAR-signature surface; v2/v3 cover every supported device.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = when {
                releaseSigning != null -> signingConfigs.getByName("release")
                developmentKeystore.exists() -> signingConfigs.getByName("development")
                else -> null
            }
        }
    }

    buildFeatures {
        compose = true
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    "templateApk"(project(":shell"))

    implementation(project(":config"))
    implementation(libs.kotlinx.serialization.json)

    // APK rewriting and signing, both pure Java and both usable on device.
    implementation(libs.arsclib)
    implementation(libs.apksig)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
