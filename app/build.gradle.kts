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
    namespace = "io.github.forrcaho.patchcanvas"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.forrcaho.patchcanvas"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
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
            isMinifyEnabled = false
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
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
}
