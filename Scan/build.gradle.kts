import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// versionCode strategy: read from `version.properties` at the repo root and
// auto-incremented after every successful assemble*/bundle* by the
// `bumpVersionCode` finalizer below. Mirrors the iOS app's `agvtool bump`
// post-build action — every Build button press bumps the number.
//
// The file IS tracked in git so the bump propagates between machines
// (commit it after a release, just like you'd commit the iOS pbxproj diff).
//
// versionName is human (semver). Defaults to "1.0" but can be overridden
// at the command line — the release workflow passes `-PversionName=1.2.3`
// derived from the v1.2.3 git tag, so a tag = a published version name.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use(::load)
    }
}
val storedVersionCode: Int = run {
    val raw = versionProps.getProperty("versionCode")
    if (raw == null) {
        // No file present (e.g. fresh checkout that forgot to commit
        // version.properties, or a shallow CI clone). Fail loudly here
        // instead of defaulting to a small number that Play will already
        // have reserved from a previous upload.
        throw GradleException(
            "version.properties is missing or has no `versionCode` entry. " +
                "Either commit ${versionPropsFile.relativeTo(rootProject.projectDir)} " +
                "to the repo, or pass an explicit `-PversionCode=N` (where N is " +
                "strictly greater than every versionCode previously uploaded to Play)."
        )
    }
    val override = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    override ?: raw.toInt()
}

val resolvedVersionName: String =
    (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0"

// Allow opting out of the bump for a single build, e.g. when running a
// throwaway test or when CI does not want the local file mutated:
//     ./gradlew :Scan:bundleRelease -PnoBump
val skipVersionBump: Boolean = project.hasProperty("noBump")

// Resolve release signing material from (in order):
//   1. `keystore.properties` next to the root build file (developer machines).
//   2. SCAN_KEYSTORE_PATH / SCAN_KEYSTORE_PASSWORD / SCAN_KEY_ALIAS /
//      SCAN_KEY_PASSWORD environment variables (CI).
// Returns `null` when nothing is configured — the release build then falls
// back to the debug signing config so `assembleRelease` still works locally
// without keys (it just won't be uploadable to Play).
val releaseSigning: Map<String, String>? = run {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val p = Properties().apply { propsFile.inputStream().use(::load) }
        mapOf(
            "storeFile" to (p.getProperty("storeFile") ?: return@run null),
            "storePassword" to (p.getProperty("storePassword") ?: return@run null),
            "keyAlias" to (p.getProperty("keyAlias") ?: return@run null),
            "keyPassword" to (p.getProperty("keyPassword") ?: return@run null),
        )
    } else {
        val path = System.getenv("SCAN_KEYSTORE_PATH")
        val storePassword = System.getenv("SCAN_KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("SCAN_KEY_ALIAS")
        val keyPassword = System.getenv("SCAN_KEY_PASSWORD")
        if (path != null && storePassword != null && keyAlias != null && keyPassword != null) {
            mapOf(
                "storeFile" to path,
                "storePassword" to storePassword,
                "keyAlias" to keyAlias,
                "keyPassword" to keyPassword,
            )
        } else null
    }
}

android {
    namespace = "me.nettrash.scan"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.nettrash.scan"
        minSdk = 28
        targetSdk = 36
        versionCode = storedVersionCode
        versionName = resolvedVersionName
    }

    signingConfigs {
        releaseSigning?.let { sig ->
            create("release") {
                storeFile = rootProject.file(sig.getValue("storeFile"))
                storePassword = sig.getValue("storePassword")
                keyAlias = sig.getValue("keyAlias")
                keyPassword = sig.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // If `keystore.properties` / env-vars aren't present, this stays
            // null and AGP falls back to the debug signing config so local
            // `assembleRelease` still works (just not uploadable to Play).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric needs Android resources + the system AndroidManifest.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/*.so"
            )
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Serialization & Network
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // CameraX + ML Kit barcode scanning
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.mlkit.barcode.scanning)

    // ZXing — generation only
    implementation(libs.zxing.core)

    // Permissions
    implementation(libs.accompanist.permissions)

    debugImplementation(libs.androidx.ui.tooling)

    // Unit tests — JUnit 4 + Robolectric (so parsers that touch
    // android.net.Uri / android.util.Base64 work in the JVM test runtime).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
}

// ---- versionCode auto-bump ----------------------------------------------
//
// Mirrors the iOS app's `agvtool bump` post-build action: every successful
// `assembleDebug`, `assembleRelease`, `bundleDebug`, or `bundleRelease`
// rewrites `version.properties` with `versionCode + 1`. The new value is
// effective on the *next* build (the current build keeps the value it was
// configured with, since defaultConfig is locked at configuration time).
//
// `doLast` only fires when the parent task's actions complete successfully,
// so failed builds don't bump. The AtomicBoolean guards against double-
// bumping when more than one of the listed tasks runs in a single
// invocation (e.g. `./gradlew assembleRelease bundleRelease`).
//
// Opt out per-build with `-PnoBump`.
val bumpedInThisInvocation = AtomicBoolean(false)
afterEvaluate {
    if (skipVersionBump) return@afterEvaluate
    listOf(
        "assembleDebug",
        "assembleRelease",
        "bundleDebug",
        "bundleRelease",
    ).forEach { taskName ->
        tasks.findByName(taskName)?.doLast {
            if (!bumpedInThisInvocation.compareAndSet(false, true)) return@doLast
            val newValue = storedVersionCode + 1
            versionProps.setProperty("versionCode", newValue.toString())
            versionPropsFile.outputStream().use {
                versionProps.store(
                    it,
                    "Auto-incremented after build. Edit only if you know what you're doing."
                )
            }
            logger.lifecycle(
                ":Scan: bumped versionCode $storedVersionCode -> $newValue (effective next build)"
            )
        }
    }
}
