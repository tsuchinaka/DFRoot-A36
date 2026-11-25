plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing values come from Gradle properties or env; the historical local
// defaults keep sideload builds working without a secret store.
fun secret(prop: String, env: String, fallback: String): String {
    val property = (project.findProperty(prop) as String?)?.takeIf { it.isNotBlank() }
    return property
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: fallback
}

// R8 is enabled by default. `-Pdfroot.minify=false` is the internal fast build
// used when only a signed, installable artifact is needed.
val minifyRelease = providers.gradleProperty("dfroot.minify")
    .map { it.toBoolean() }
    .getOrElse(true)

android {
    namespace = "df.root"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "df.root"
        minSdk = 33
        targetSdk = 36
        versionCode = 32
        versionName = "4.3.0-ui"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("keystore") {
            storeFile = file("keystore.jks")
            storePassword = secret("dfroot.storePassword", "DFROOT_STORE_PASSWORD", "android")
            keyAlias = secret("dfroot.keyAlias", "DFROOT_KEY_ALIAS", "dfroot")
            keyPassword = secret("dfroot.keyPassword", "DFROOT_KEY_PASSWORD", "android")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("keystore")
        }
        release {
            signingConfig = signingConfigs.getByName("keystore")
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
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

    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    // Expressive APIs live here (MaterialExpressiveTheme, MotionScheme, ShortNavigationBar, ...)
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
