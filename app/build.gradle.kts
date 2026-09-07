plugins {
    id("com.android.application")
    // AGP 9.x: Kotlin built-in — plugin kotlin.android tidak dipasang lagi.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lyreon.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.lyreon.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "3.5.0"
    }

    val keystoreFile = file("${rootProject.projectDir}/debug.keystore")

    // `debug.keystore` TIDAK ikut ter-commit (dan tidak dibuat runner CI). Tanpa
    // fallback di bawah, signingConfig "release" tidak punya `storeFile` sama
    // sekali → `packageRelease` mati dengan
    //   NullPointerException: SigningConfig "release" is missing required property "storeFile"
    // sementara build debug tetap lolos (AGP membuat ~/.android/debug.keystore
    // sendiri untuk config debug bawaan). Inilah penyebab CI merah di langkah
    // "Build Release APK" — bukan masalah kode aplikasi.
    val hasKeystore = keystoreFile.exists()

    signingConfigs {
        getByName("debug") {
            if (hasKeystore) {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (hasKeystore) {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ada keystore → pakai config release. Tidak ada → numpang config debug
            // supaya APK release tetap ter-bentuk (bertanda tangan debug, cukup untuk
            // uji coba; rilis publik tetap harus menandatangani dengan keystore sendiri).
            signingConfig =
                if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }

    // AGP 9.x built-in Kotlin: blok kotlin{} pindah KE DALAM android{}
    // (referensi: Metrolist dengan AGP 9.3.0 + Kotlin 2.4.10 identik).
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
        }
    }
}

dependencies {
    // --- Compose (BOM 2026.01.01, Januari 2026) ---
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // SAF DocumentFile untuk pemindai folder musik kustom (menembus .nomedia)
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // --- Media3 ExoPlayer + MediaSession (background playback) — v1.10.1 (Juli 2026) ---
    val media3 = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // HLS (m3u8): jalur putar anonim yang tidak menuntut poToken GVS. Tanpa modul
    // ini DefaultMediaSourceFactory tidak bisa membuat HlsMediaSource dan manifest
    // HLS dari klien web_safari/tv_simply gagal diendus sebagai audio progresif.
    implementation("androidx.media3:media3-exoplayer-hls:$media3")

    // --- YouTube extractor (search, saran, metadata, audio stream) ---
    // Upstream TeamNewPipe/NewPipeExtractor v0.25+ tidak lagi ter-publish
    // dengan benar di JitPack (modules kosong → semua file 404), sehingga
    // ekosistem (Metrolist dkk.) bermigrasi ke fork terpelihara ini (Agu 2026).
    // API identik: package org.schabi.newpipe.extractor.*
    // Engine stream Juni 2026 (HEAD fork) — terbukti paling tersedia di lapangan:
    // retry safari player saat streaming data kosong, klien AndroidVR,
    // dan fix playlist/uploaders. (v3.3.0 sempat ter-pin ke snapshot Okt 2025
    // yang lebih TUA — itulah penyebab stream sering "tak tersedia".)
    implementation("com.github.MetrolistGroup:MetrolistExtractor:3cd334185d68d4e5e057dcb9046191b76e4e19ef")

    // --- Networking ---
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // --- Fallback extractor: engine JS ringan untuk menjalankan fungsi decipher
    // (s & n) yang dipanen dari base.js YouTube — jalur InnerTube langsung ke Google,
    // dipakai ketika MetrolistExtractor gagal/stream kosong. API Rhino stabil sejak lama.
    implementation("org.mozilla:rhino:1.7.15")

    // --- Persistence ---
    val room = "2.8.4"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
