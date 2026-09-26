plugins {
    id("com.android.application")
    // Since Kotlin 2.0 the Compose compiler ships with Kotlin itself and is applied
    // as a plugin, replacing the old composeOptions/kotlinCompilerExtensionVersion
    // pair that had to be version-matched by hand.
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Release signing -------------------------------------------------------
// Credentials come from ~/.gradle/gradle.properties on a dev machine, or from
// environment variables in CI. Neither lives in this repository. If no
// credentials are present the release build is left unsigned rather than
// failing, so a fresh clone can still run `assembleDebug` and `test`.
val storeFilePath: String? =
    (findProperty("RELEASE_STORE_FILE") as String?) ?: System.getenv("RELEASE_STORE_FILE")
val storePw: String? =
    (findProperty("RELEASE_STORE_PASSWORD") as String?) ?: System.getenv("RELEASE_STORE_PASSWORD")
val keyAliasName: String? =
    (findProperty("RELEASE_KEY_ALIAS") as String?) ?: System.getenv("RELEASE_KEY_ALIAS")
val keyPw: String? =
    (findProperty("RELEASE_KEY_PASSWORD") as String?) ?: System.getenv("RELEASE_KEY_PASSWORD")

val hasReleaseSigning = storeFilePath != null && file(storeFilePath).exists() &&
    storePw != null && keyAliasName != null && keyPw != null

// Version metadata: overridable from CI so a tagged release stamps the APK with
// the tag name, while a local build keeps the checked-in defaults.
val appVersionName: String = (findProperty("appVersionName") as String?) ?: "1.0"
val appVersionCode: Int = ((findProperty("appVersionCode") as String?) ?: "1").toInt()

android {
    namespace = "io.github.forrcaho.patchgarden"
    compileSdk = 37

    // Pinned rather than left to AGP's default, so a second machine and CI
    // build against the same toolchain instead of whatever they resolve.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.forrcaho.patchgarden"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        // The reference device reports arm64-v8a and nothing else, and every phone
        // this targets is 64-bit. Building one ABI keeps the APK small and removes a
        // 32-bit DSP path nobody would ever exercise.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // Oboe's prefab is built against the shared STL and the NDK defaults to
                // the static one. Mixing them is rejected at configure time rather than
                // failing mysteriously later, which is the better error.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = storePw
                keyAlias = keyAliasName
                keyPassword = keyPw
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // R8, and the resources nothing refers to. 25MB to what the app uses: the two dex
            // files were 22.6MB of Compose and Material 3 kept whole. The JNI entry points are
            // looked up by name, and survive because the default rules keep every class with a
            // native method, and those methods, unrenamed; the engine calls nothing back but
            // java.lang.String, and nothing here uses reflection.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which gates the debug audio capture.
        buildConfig = true
        // Oboe ships its headers and .so as a prefab package inside its AAR, so there
        // is no source checkout to vendor and no submodule to keep in step.
        prefab = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // android.util.Log is a throwing stub in the unit-test android.jar; the
            // error paths in PatchStore call it deliberately, so let it no-op instead.
            isReturnDefaultValues = true
            // Robolectric's gesture tests host the real composable, which needs the manifest
            // and resources merged as the app would have them.
            isIncludeAndroidResources = true
            // Robolectric reaches into FileDescriptor's internals, which JDK 17 and later
            // close to it unless they are opened by name.
            all {
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Transport only: Oboe gives a stream and a realtime callback, no DSP and no graph.
    implementation("com.google.oboe:oboe:1.10.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Gesture tests: the real PatchCanvas, driven by real pointer events, on the JVM. In the
    // unit suite rather than androidTest so they run with everything else and need no
    // device -- every gesture fault so far was found by a finger, and a suite that needs a
    // phone plugged in is one that does not get run.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    // The empty activity createComposeRule hosts its content in. Debug only.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // The android.jar used for unit tests stubs org.json with methods that throw, so the
    // real implementation goes on the test classpath ahead of it.
    testImplementation("org.json:json:20250107")
}

// ---- Host-side audio graph tests ---------------------------------------------
// graph.cpp and nodes.cpp depend on nothing from Android or Oboe, so they compile and
// run on the build machine. Audio bugs are miserable to diagnose on a device, and the
// evaluation order is the part most worth pinning down before it ever gets there. Run
// under ASan and UBSan, which is how the missing Graph destructor was found.

val hostCxx: String? = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.map { File(it, "g++") }
    ?.firstOrNull { it.canExecute() }
    ?.absolutePath

val nativeGraphTest = tasks.register<Exec>("nativeGraphTest") {
    group = "verification"
    description = "Compiles and runs the audio graph and node tests on the host toolchain."

    // Skipped rather than failed where there is no host compiler, so a machine that
    // only builds the app is never blocked by a test it cannot run.
    onlyIf { hostCxx != null }

    val outDir = layout.buildDirectory.dir("native-test").get().asFile
    val graphBinary = File(outDir, "graph_test").absolutePath
    val nodeBinary = File(outDir, "node_test").absolutePath

    workingDir = projectDir
    inputs.files(
        fileTree("src/main/cpp") { include("**/*.cpp", "**/*.h") },
        fileTree("src/test/cpp") { include("**/*.cpp") },
    )
    outputs.dir(outDir)

    val cxx = hostCxx ?: "g++"
    val vendorDir = File(outDir, "vendor").absolutePath
    val sanitize = "-fsanitize=address,undefined"

    val script = buildString {
        append("mkdir -p ").append(vendorDir).append(" && ")
        // Vendored upstream is compiled to its own standards, not ours. -w rather than
        // patching DaisySP to satisfy -Wextra, which would mean carrying a diff forever.
        append("for f in src/main/cpp/vendor/daisysp/*.cpp src/main/cpp/vendor/tinysoundfont/*.cpp; do ")
        // Nor to our sanitizers' every opinion: TinySoundFont shifts a negative generator
        // amount left while parsing, which is defined in C++20 and on every compiler this
        // builds with, and UBSan reported it on every load.
        append(cxx).append(" -std=c++17 -O1 -w ").append(sanitize).append(" -fno-sanitize=shift")
        append(" -I src/main/cpp/vendor/daisysp -I src/main/cpp/vendor/tinysoundfont -c \"${'$'}f\"")
        append(" -o ").append(vendorDir).append("/\"${'$'}(basename \"${'$'}f\" .cpp)\".o")
        append(" || exit 1; done && ")
        append(cxx)
        append(" -std=c++17 -O1 -Wall -Wextra -Werror ").append(sanitize)
        append(" -I src/main/cpp -isystem src/main/cpp/vendor/daisysp -isystem src/main/cpp/vendor/tinysoundfont")
        append(" src/test/cpp/graph_test.cpp")
        append(" src/main/cpp/graph.cpp")
        append(" src/main/cpp/nodes.cpp")
        append(" src/main/cpp/soundfont.cpp")
        append(" src/main/cpp/processors.cpp")
        append(" ").append(vendorDir).append("/*.o")
        append(" -o ").append(graphBinary)
        append(" && ")
        append(cxx)
        append(" -std=c++17 -O1 -Wall -Wextra -Werror ").append(sanitize)
        append(" -I src/main/cpp -isystem src/main/cpp/vendor/daisysp -isystem src/main/cpp/vendor/tinysoundfont")
        append(" src/test/cpp/node_test.cpp")
        append(" src/main/cpp/nodes.cpp")
        append(" src/main/cpp/soundfont.cpp")
        append(" src/main/cpp/processors.cpp")
        append(" ").append(vendorDir).append("/*.o")
        append(" -o ").append(nodeBinary)
        append(" && ").append(graphBinary)
        append(" && ").append(nodeBinary)
    }
    commandLine("bash", "-c", script)
}

tasks.withType<Test>().configureEach {
    dependsOn(nativeGraphTest)
}
