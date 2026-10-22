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

    // Produce ABI-specific APKs to avoid making each phone download native
    // libraries for architectures it cannot run. Keep a universal APK as a
    // compatibility fallback for architectures not listed here.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    defaultConfig {
        applicationId = "com.dpadsms"
        minSdk = 23
        targetSdk = 34
        versionCode = getSecret("EXPERIMENTAL_VERSION_CODE")?.toIntOrNull() ?: 1058000
        versionName = "0.5.8"
        buildConfigField("String", "UPDATE_CHANNEL", "\"production\"")

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
            buildConfigField("String", "UPDATE_CHANNEL", "\"production\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("experimental") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("release")
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-experimental"
            buildConfigField("String", "UPDATE_CHANNEL", "\"experimental\"")
        }
        debug {
            applicationIdSuffix = providers.gradleProperty("debugApplicationIdSuffix").orNull
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

    // Durable background receive processing
    implementation("androidx.work:work-runtime-ktx:2.9.0")

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

// A clean checkout must include the encryption engine for every supported Android ABI.
val buildDumberCore by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("bash", "scripts/build-dumber-core.sh")
    environment("DUMBER_RUST_TARGETS", providers.gradleProperty("dumberRustTargets").orElse("aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android").get())
    inputs.files(fileTree(rootProject.file("vendor/dumber-chat-core")) { exclude("target/**", ".git/**") })
    inputs.file(rootProject.file("scripts/build-dumber-core.sh"))
    inputs.property("targets", providers.gradleProperty("dumberRustTargets").orElse("aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android"))
    outputs.files(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").map { file("src/main/jniLibs/$it/libdumber_chat_core.so") })
}
tasks.named("preBuild") { dependsOn(buildDumberCore) }
