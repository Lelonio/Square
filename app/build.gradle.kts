import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * ABIs to build the native core for.
 *
 * arm64 covers every phone made in the last decade, and each extra ABI is a full
 * extra Rust build (~2 min from cold), so the default is deliberately just the
 * one. Add "x86_64" for the emulator and "armeabi-v7a" for pre-2015 hardware.
 */
val nativeAbis = listOf("arm64-v8a")

/** NDK used for both AGP and the Cargo cross-build; keep the two in step. */
val ndkVersionForCargo = "28.2.13676358"

/** Maps Android ABI names to Rust target triples. */
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)

/**
 * The release signing details, when there are any.
 *
 * Kept in `keystore.properties` beside the project and never committed: a
 * keystore in a repository is a keystore anyone can sign as you with. When the
 * file is absent — a fresh clone, or anyone building this who is not its
 * author — the release build falls back to the debug key, which is fine for
 * running it and useless for distributing it.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "dev.lelonio.square"
    compileSdk = 37
    ndkVersion = ndkVersionForCargo

    defaultConfig {
        applicationId = "dev.lelonio.square"
        // cpal's Android host is AAudio, which the ndk crate gates at API 26.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += nativeAbis
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The real key when there is one, the debug key otherwise — see
            // keystoreProperties above. A build signed with the debug key runs
            // and can be measured; it must never be the one handed to anyone,
            // because every machine's debug key is the same well-known one.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }

        /**
         * What to build while working on the app.
         *
         * A profile of a one-line-change build: R8 3m43s of a 4m12s total, then
         * lint-vital at 50s and the Compose mapping at 32s. Compiling the actual
         * Kotlin was ten seconds. Shrinking a build that is going straight onto
         * a test phone buys nothing and costs almost the entire wall clock.
         *
         * Not `debug`, though — a debuggable build turns off the ART optimiser
         * and leaves Compose's own debug instrumentation in, so scrolling and
         * animation measure far worse than they really are, which is the thing
         * this app is mostly being judged on. This is release without the
         * shrinker: same runtime behaviour, none of the wait.
         *
         * Verify on `release` before shipping — R8 is also what would surface a
         * missing keep rule (reflection over the Retrofit and serialization
         * models above all), and that failure only ever appears in a minified
         * build.
         */
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // Runs on demand (`./gradlew lint`) rather than inside every release
        // build. It is 50 seconds of a build whose output is a local test
        // install, and it has never been the thing that caught a problem here.
        checkReleaseBuilds = false
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
        // Off by default since AGP 8; SquareApplication reads BuildConfig.DEBUG.
        buildConfig = true
    }

    // Bungee and its JNI wrapper. The Rust core is not built here — it is
    // cross-compiled by the cargoBuild task and dropped into jniLibs.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    packaging {
        // The Rust cdylib is already stripped by the release profile; letting
        // Gradle re-strip it with the wrong tool breaks the arm64 build.
        jniLibs.keepDebugSymbols += "**/libsquarecore.so"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.phosphor)
}

/** Pinned so a build is not at the mercy of upstream's next commit. */
val bungeeTag = "v2.4.24"

/**
 * Clones Bungee, the time-stretching library behind the speed and pitch
 * controls.
 *
 * Fetched rather than vendored: Bungee is MPL-2.0, and keeping the licensed
 * sources exactly as upstream published them — pinned to a tag, never edited —
 * keeps the obligation simple. The build only compiles them; the patched copy
 * of librespot under native/vendor is vendored precisely because it *is*
 * modified, and that difference is the reason for treating the two differently.
 */
val fetchBungee by tasks.registering {
    group = "build"
    description = "Clones the Bungee time-stretch library if it is not present"

    // Everything is resolved here rather than inside the action: a task action
    // that touches the build script gets captured with it, which the
    // configuration cache refuses to serialize.
    val target = rootProject.file("native-dsp/bungee")
    val marker = target.resolve("bungee/Bungee.h")
    val tag = bungeeTag
    outputs.dir(target)

    doLast {
        if (marker.exists()) return@doLast
        target.deleteRecursively()
        target.parentFile.mkdirs()

        val process = ProcessBuilder(
            "git", "clone",
            "--depth", "1",
            "--branch", tag,
            "--recurse-submodules", "--shallow-submodules",
            "https://github.com/bungee-audio-stretch/bungee.git",
            target.absolutePath,
        ).inheritIO().start()

        check(process.waitFor() == 0) { "could not clone Bungee $tag into $target" }
    }
}


/**
 * Cross-compiles the Rust core and drops the resulting `.so` files straight into
 * `jniLibs`.
 *
 * This shells out to `cargo-ndk` instead of using externalNativeBuild because
 * the crate is Cargo-driven, not CMake-driven. Install it once with
 * `cargo install cargo-ndk` and add the targets with `rustup target add`.
 */
val cargoBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles the librespot core for all configured ABIs"

    val nativeDir = rootProject.file("native")
    workingDir = nativeDir

    // Gradle can skip this entirely when nothing in the crate changed.
    inputs.dir(nativeDir.resolve("src"))
    inputs.file(nativeDir.resolve("Cargo.toml"))
    inputs.file(nativeDir.resolve("Cargo.lock"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))

    val outputDir = layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath
    val abiArgs = nativeAbis.flatMap { listOf("-t", it) }
    // -P 26 must match minSdk: libaaudio.so only exists in the sysroot from API
    // 26 up, and cargo-ndk otherwise links against API 21 and fails with
    // "unable to find library -laaudio".
    // The rustup cargo by absolute path, not whatever `cargo` resolves to first.
    // A Homebrew rust earlier on PATH has neither the Android targets nor a new
    // enough rustc for the dependency tree, and the failure it produces
    // ("requires rustc 1.88") points at the crates rather than at the toolchain.
    val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        .takeIf { it.canExecute() }?.absolutePath ?: "cargo"
    commandLine(
        listOf(cargo, "ndk") + abiArgs +
            listOf("-P", "26", "-o", outputDir, "build", "--release"),
    )

    // Everything is resolved here rather than in a doFirst block: a task action
    // closure would capture the build script instance, which the configuration
    // cache refuses to serialize.
    val ndkDir = File(android.sdkDirectory, "ndk/$ndkVersionForCargo")
    val unmappedAbis = nativeAbis.filterNot { rustTargets.containsKey(it) }
    require(unmappedAbis.isEmpty()) { "No Rust target mapped for ABI(s): $unmappedAbis" }
    require(ndkDir.isDirectory) {
        "NDK $ndkVersionForCargo not found at $ndkDir — install it with " +
            "sdkmanager --install \"ndk;$ndkVersionForCargo\""
    }

    // cargo-ndk finds the toolchain through this; AGP's own ndkVersion is not
    // visible to an external process.
    environment("ANDROID_NDK_HOME", ndkDir.absolutePath)

    // cargo-ndk re-invokes plain `cargo` for each target, so pointing the task
    // at the rustup binary is not enough on its own: the child would still pick
    // up a Homebrew rust earlier on PATH, which has neither the Android targets
    // nor a new enough rustc, and reports it as "requires rustc 1.88".
    File(System.getProperty("user.home"), ".cargo/bin").takeIf { it.isDirectory }?.let {
        environment("PATH", "${it.absolutePath}:${System.getenv("PATH")}")
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoBuild) }

// CMake reads Bungee's sources at configure time, so the clone has to have
// happened before any of the native build tasks run.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchBungee) }
