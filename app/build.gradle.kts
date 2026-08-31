import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val nativeAbis = listOf("arm64-v8a")
val ndkVersionForCargo = "28.2.13676358"
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)

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
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "2.0.1"
        ndk { abiFilters += nativeAbis }
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
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint { checkReleaseBuilds = false }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    packaging { jniLibs.keepDebugSymbols += "**/libsquarecore.so" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.newpipe.extractor)
    implementation(project(":innertube"))
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.phosphor)

    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.21")
    coreLibraryDesugaring(libs.desugaring)
}

val bungeeTag = "v2.4.24"

val fetchBungee by tasks.registering {
    group = "build"
    description = "Clones the Bungee time-stretch library if it is not present"
    val target = rootProject.file("native-dsp/bungee")
    val marker = target.resolve("bungee/Bungee.h")
    val tag = bungeeTag
    outputs.dir(target)
    doLast {
        if (marker.exists()) return@doLast
        target.deleteRecursively()
        target.parentFile.mkdirs()
        val process = ProcessBuilder(
            "git", "clone", "--depth", "1", "--branch", tag, "--recurse-submodules", "--shallow-submodules",
            "https://github.com/bungee-audio-stretch/bungee.git", target.absolutePath,
        ).inheritIO().start()
        check(process.waitFor() == 0) { "could not clone Bungee $tag into $target" }
    }
}

val cargoBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles the librespot core for all configured ABIs"
    val nativeDir = rootProject.file("native")
    workingDir = nativeDir
    inputs.dir(nativeDir.resolve("src"))
    inputs.dir(nativeDir.resolve("vendor"))
    inputs.file(nativeDir.resolve("Cargo.toml"))
    inputs.file(nativeDir.resolve("Cargo.lock"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    val outputDir = layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath
    val abiArgs = nativeAbis.flatMap { listOf("-t", it) }
    val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        .takeIf { it.canExecute() }?.absolutePath ?: "cargo"
    commandLine(listOf(cargo, "ndk") + abiArgs + listOf("-P", "26", "-o", outputDir, "build", "--release"))
    val ndkDir = File(android.sdkDirectory, "ndk/$ndkVersionForCargo")
    val unmappedAbis = nativeAbis.filterNot { rustTargets.containsKey(it) }
    require(unmappedAbis.isEmpty()) { "No Rust target mapped for ABI(s): $unmappedAbis" }
    require(ndkDir.isDirectory) {
        "NDK $ndkVersionForCargo not found at $ndkDir — install it with sdkmanager --install \"ndk;$ndkVersionForCargo\""
    }
    environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
    File(System.getProperty("user.home"), ".cargo/bin").takeIf { it.isDirectory }?.let {
        environment("PATH", "${it.absolutePath}:${System.getenv("PATH")}")
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoBuild) }

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchBungee) }
