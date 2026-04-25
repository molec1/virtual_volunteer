import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val faceMatchingTestdataDir: File = rootProject.file("testdata/face_matching")

// AGP reliably merges androidTest assets from under app/build; copying repo-root testdata here avoids
// missing same_persons/ in the test APK when using an external srcDir.
val generatedFaceMatchingAndroidTestAssets =
    layout.buildDirectory.dir("generated/face-matching-androidTest-assets")

val prepareFaceMatchingAndroidTestAssets = tasks.register("prepareFaceMatchingAndroidTestAssets") {
    description =
        "Copies root testdata/face_matching into app/build/... for androidTest asset merge (or leaves an empty dir)."
    outputs.upToDateWhen { false }
    val outDir = generatedFaceMatchingAndroidTestAssets.get().asFile
    outputs.dir(outDir)
    doLast {
        outDir.deleteRecursively()
        if (faceMatchingTestdataDir.exists()) {
            faceMatchingTestdataDir.copyRecursively(outDir)
        } else {
            outDir.mkdirs()
        }
    }
}

android {
    namespace = "com.virtualvolunteer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.virtualvolunteer.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-mvp"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        getByName("androidTest") {
            assets.srcDir(generatedFaceMatchingAndroidTestAssets)
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        noCompress += "tflite"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Gradle 8+: merge*AndroidTest*Assets reads generated assets; must depend on prepare explicitly (AGP has no srcDir+builtBy).
afterEvaluate {
    listOf("mergeDebugAndroidTestAssets", "mergeReleaseAndroidTestAssets").forEach { taskName ->
        tasks.findByName(taskName)?.dependsOn(prepareFaceMatchingAndroidTestAssets)
    }
}

dependencies {
    val navVersion = "2.8.9"
    val roomVersion = "2.6.1"
    val lifecycleVersion = "2.8.7"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // JitPack builds from https://github.com/Baseflow/PhotoView (tag 2.3.0)
    implementation("com.github.Baseflow:PhotoView:2.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}

