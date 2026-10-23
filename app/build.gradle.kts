import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.sentry)
}

// Signing: a private keystore (never committed), from keystore.properties locally or the
// LAWALLET_* environment in CI. Its SHA-256 (or Play App Signing's, once enrolled) is what an
// instance lists in /.well-known/assetlinks.json for passkeys + App Links.
// Without either, builds fall back to the default debug key.
val keystoreProps = Properties().apply {
    // keystore.properties at the repo root, or LAWALLET_SIGNING_PROPERTIES=/path/to/x.properties
    // (storeFile may be relative to that file) — secrets never need to live in the repo.
    val propsFile = System.getenv("LAWALLET_SIGNING_PROPERTIES")?.let(::File) ?: rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
        getProperty("storeFile")?.let { sf ->
            val f = File(sf)
            if (!f.isAbsolute || !f.exists()) setProperty("storeFile", File(propsFile.parentFile, f.name).absolutePath)
        }
    }
    System.getenv("LAWALLET_KEYSTORE")?.let {
        setProperty("storeFile", it)
        setProperty("storePassword", System.getenv("LAWALLET_KEYSTORE_PASSWORD"))
        setProperty("keyAlias", System.getenv("LAWALLET_KEY_ALIAS"))
        setProperty("keyPassword", System.getenv("LAWALLET_KEY_PASSWORD"))
    }
}

// CI passes -PversionCode=<n> -PversionName=<tag>; local builds use the defaults.
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
val appVersionName = (findProperty("versionName") as String?)?.removePrefix("v") ?: "1.0.0"
// GitHub/Zapstore APKs (-PabiSplits, or -PselfUpdate for local testing) download and install their
// own updates. Only they get REQUEST_INSTALL_PACKAGES: Play forbids it and updates Play installs itself.
val selfUpdate = hasProperty("abiSplits") || hasProperty("selfUpdate")

android {
    namespace = "io.lawallet.wallet"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.lawallet.wallet"
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_INSTANCE", "\"https://beta.lawallet.io\"")
        // Crash reports (opt-in): CI passes -PsentryDsn. Local and F-Droid source builds have none,
        // so the reporter never starts there.
        buildConfigField("boolean", "SELF_UPDATE", "$selfUpdate")
        // GitHub's latest-release API; -PupdateFeed points a test build at a local copy.
        buildConfigField("String", "UPDATE_FEED", "\"${findProperty("updateFeed") ?: "https://api.github.com/repos/lawalletio/android-wallet/releases/latest"}\"")
        // Beta: crash reports are always on (no opt-out). Flip to false at the stable release.
        buildConfigField("boolean", "BETA", "true")
        buildConfigField("String", "SENTRY_DSN", "\"${findProperty("sentryDsn") ?: ""}\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("lawallet") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("lawallet")?.let { signingConfig = it }
            // `-PidSuffix=.preview` installs a side-by-side copy (fresh state) for UI checks.
            (project.findProperty("idSuffix") as String?)?.let { applicationIdSuffix = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Native symbols ship inside the AAB so Play can symbolicate native crashes/ANRs.
            ndk { debugSymbolLevel = "FULL" }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("lawallet") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    bundle {
        // The in-app language picker switches to any bundled locale, so every device needs all of
        // them — Play must not strip unused languages from the install.
        language { enableSplit = false }
    }
    // GitHub/Zapstore APKs: -PabiSplits produces one APK per ABI (Play uses the AAB instead).
    if (hasProperty("abiSplits")) splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    if (selfUpdate) sourceSets {
        getByName("debug").manifest.srcFile("src/selfUpdate/AndroidManifest.xml")
        getByName("release").manifest.srcFile("src/selfUpdate/AndroidManifest.xml")
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    jvmToolchain(17)
}

// Only the R8 mapping upload (CI, when SENTRY_AUTH_TOKEN is set). No auto-installed integrations
// or bytecode instrumentation: OkHttp URLs carry invoices and LNURL k1s (docs/monitoring-plan.md).
sentry {
    org.set("la-crypta")
    projectName.set("lawallet-android")
    autoInstallation { enabled.set(false) }
    tracingInstrumentation { enabled.set(false) }
    includeSourceContext.set(false)
    includeDependenciesReport.set(false)
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(!System.getenv("SENTRY_AUTH_TOKEN").isNullOrEmpty())
    ignoredBuildTypes.set(setOf("debug"))
    telemetry.set(false)
}

// Profile/benchmark builds (created by the baseline-profile plugin) install side by side as
// ".bench", so measuring never replaces — or, when Gradle uninstalls after the run, wipes — a
// real logged-in install. Set on the variant: the plugin configures these build types late.
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "nonMinifiedRelease" || variant.buildType == "benchmarkRelease") {
            variant.applicationId.set("io.lawallet.wallet.bench")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.secp256k1.android)
    implementation(libs.bouncycastle)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.network)
    implementation(libs.lottie.compose)
    implementation(libs.lucide)
    implementation(libs.haze)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.sentry.android.core)
    implementation(libs.play.app.update)

    testImplementation(libs.junit)
    testImplementation(libs.secp256k1.jvm)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.test.rules)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary)
    baselineProfile(project(":baselineprofile"))
}
