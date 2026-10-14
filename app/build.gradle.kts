plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace  = "com.biel.ironmontracker"
    compileSdk = 36

    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.biel.ironmontracker"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 1
        versionName   = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DENABLE_SDL=OFF",
                    "-DENABLE_QT=OFF",
                    "-DBUILD_SDL=OFF",
                    "-DBUILD_QT=OFF",
                    "-DBUILD_LIBRETRO=OFF",
                    "-DBUILD_PERF=OFF",
                    "-DBUILD_TEST=OFF",
                    "-DBUILD_SUITE=OFF",
                    "-DBUILD_CINEMA=OFF",
                    "-DBUILD_HEADLESS=OFF",
                    "-DUSE_FFMPEG=OFF",
                    "-DUSE_DISCORD_RPC=OFF",
                    "-DUSE_EDITLINE=OFF",
                    "-DUSE_ELF=OFF",
                    "-DUSE_EPOXY=OFF",
                    "-DUSE_LIBZIP=OFF",
                    "-DUSE_JSON_C=OFF",
                    "-DUSE_FREETYPE=OFF",
                    "-DUSE_SQLITE3=OFF",
                    "-DUSE_MINIZIP=OFF",
                    "-DENABLE_GDB_STUB=OFF",
                    "-DENABLE_DEBUGGERS=OFF",
                    "-DM_CORE_GB=OFF",
                    "-DUSE_LZMA=OFF",
                    "-DENABLE_SCRIPTING=ON",
                    "-DUSE_LUA=5.4",
                    "-DUSE_PNG=OFF",
                    "-DBUILD_SHARED=OFF",
                    "-DBUILD_STATIC=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

    buildFeatures {
        compose = true
    }

    // Vendored from PokemonRandomizerZX (com.dabomstew.pkrandom): the actual
    // randomization engine, used to generate a fresh seed on reset. Its config
    // tables/patches are loaded as classpath resources (ResourceBundle, .ini,
    // .tbl, .ips files), so src/main/resources must be on the resources path.
    sourceSets {
        getByName("main") {
            resources.srcDirs("src/main/resources")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
