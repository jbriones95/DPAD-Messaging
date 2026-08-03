plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

fun getSecret(name: String): String? {
    val prop = providers.gradleProperty(name).orNull
    if (!prop.isNullOrBlank()) return prop
    val env = System.getenv(name)
    return if (env.isNullOrBlank()) null else env
}

android {
    namespace = "com.dpad.messaging"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dpadsms"
        minSdk = 23
        targetSdk = 34
        versionCode = 28
        versionName = "0.2.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
        arg("room.expandProjection", "true")
    }

    val releaseStoreFile = getSecret("RELEASE_STORE_FILE")
    val releaseStorePassword = getSecret("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = getSecret("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = getSecret("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            } else {
                val userHome = System.getProperty("user.home")
                storeFile = file("$userHome/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        disable.add("Instantiatable")
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.7.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // EventBus
    implementation("org.greenrobot:eventbus:3.3.1")

    // Glide (contact photos + MMS images)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")

    // JSON serialization (export/import - Phase 4)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // MMS library used by RightMessages/Fossify for carrier-compatible group MMS
    // Uses project dependency when vendored (F-Droid), falls back to jitpack otherwise
    if (findProject(":vendor:mmslib") != null) {
        implementation(project(":vendor:mmslib"))
    } else {
        implementation("org.fossify:mmslib:1.0.0")
    }

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
