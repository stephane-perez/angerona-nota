plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The person-facing version comes from the GitHub Release tag (e.g. v1.2.3), passed in
// by our own CI workflow as -PversionNameOverride=1.2.3 -PversionCodeOverride=<run
// number>. That override always wins when present.
//
// Other build environments — F-Droid's build server in particular, which checks out a
// tagged commit and runs a plain `gradle assembleRelease` with no custom flags — don't
// supply it. For those, fall back to deriving the version straight from git: the
// nearest tag for versionName, and the total commit count for versionCode (monotonic by
// construction, since it only grows as commits are added). Building with no git history
// at all (e.g. a source zip with no .git folder) falls back further to a clearly-marked
// dev version.
fun gitOutput(vararg args: String): String? = runCatching {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.ifBlank { null }
}.getOrNull()

val gitVersionName: String? = gitOutput("describe", "--tags", "--abbrev=0")?.removePrefix("v")
val gitVersionCode: Int? = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()

val appVersionName: String = (project.findProperty("versionNameOverride") as String?)
    ?: gitVersionName
    ?: "0.0.0-dev"
val appVersionCode: Int = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull()
    ?: gitVersionCode
    ?: 1

android {
    namespace = "com.stephaneperez.notepad"
    compileSdk = 36

    // Fixed debug signing key, committed to the repo (app/debug.keystore — see README,
    // "Fixed debug signing key"). Without this, every CI run signs with a fresh,
    // ephemeral debug key (a new build machine each time), which forces a full
    // uninstall before every install and destroys the on-device Keystore encryption
    // key along with it. A stable signature turns every new build into a normal
    // in-place update instead.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.stephaneperez.notepad"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Never assign signingConfigs.getByName("debug") (or any config using
            // app/debug.keystore) here. That key is committed to the repo and public
            // by design — fine for debug/CI builds (see README, "Fixed debug signing
            // key"), but it must never be used to sign anything distributed as a
            // release. As of writing, `release` has no signingConfig at all, so
            // `./gradlew assembleRelease` produces an unsigned APK; a real release
            // needs its own keystore, generated once, kept private, and never
            // committed to this repo.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // Plain JVM unit tests (app/src/test): android.jar stubs return defaults instead of
    // throwing, so classes that merely reference Android types can still be loaded.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-text-google-fonts")

    // Persist wrap / lineNumbers preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
