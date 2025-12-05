plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.playit"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.playit"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Public OpenSubtitles API key (inlined per user request). Replace with your own key or use local.properties for production.
        buildConfigField("String", "OPEN_SUBTITLES_API_KEY", "\"k6x0a9y2\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    // Ensure native libs shipped by extension artifacts are packaged.
    packaging {
        jniLibs {
            useLegacyPackaging = false
            // Include native .so files from extension artifacts. Use pickFirst to avoid duplicate-name collisions.
            pickFirsts += listOf("**/*.so")
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES")
        }
    }
}

// If a local ffmpeg AAR is present in app/libs, extract its native libs into src/main/jniLibs
// so the build will package them; run this before preBuild.
tasks.register("extractLocalFfmpegAar") {
    doLast {
        val aarFile = file("libs/media3-ffmpeg-decoder.aar")
        if (aarFile.exists()) {
            println("Found local media3-ffmpeg-decoder.aar, extracting jni libs into src/main/jniLibs...")
            copy {
                from(zipTree(aarFile)) {
                    include("jni/**")
                }
                into(file("src/main/jniLibs"))
            }
        } else {
            println("No local media3-ffmpeg-decoder.aar found in libs/")
        }
    }
}

// Ensure extraction runs before build
tasks.named("preBuild").configure {
    dependsOn("extractLocalFfmpegAar")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media3.effect)
    implementation("androidx.compose.material:material-icons-extended")
    // *** ADD THIS LINE FOR AUDIO EFFECTS LIKE BOOST ***
    implementation("androidx.media3:media3-effect:1.8.0")
    // Add these dependencies
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    implementation("androidx.activity:activity-compose:1.8.0")

    // ADD THE RESOLVABLE COMMUNITY DEPENDENCY (Jellyfin Fork) - resolves from JitPack/Maven
    // Use a local AAR if present (app/libs/media3-ffmpeg-decoder.aar); otherwise use the remote coordinate.
    if (file("libs/media3-ffmpeg-decoder.aar").exists()) {
        println("Using local media3-ffmpeg-decoder.aar from app/libs/")
        implementation(files("libs/media3-ffmpeg-decoder.aar"))
    } else {
        implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1")
    }


    // Material3 via version catalog
    implementation(libs.androidx.compose.material3)

    // Navigation for Compose
    implementation("androidx.navigation:navigation-compose:2.6.0")

    // ViewModel + Compose integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")

    // Media3 (ExoPlayer) - updated to 1.8.0 via version catalog
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)


    // DataStore for preferences/resume positions
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Retrofit for OpenSubtitles
    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    // EncryptedSharedPreferences (AndroidX Security)
    implementation("androidx.security:security-crypto:1.1.0")
    implementation(libs.androidx.ui)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
