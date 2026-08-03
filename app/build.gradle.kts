import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigningPropertiesFile = rootProject.file("release-signing/keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.exists()) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "dev.dreamplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dreamplayer"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-beta.2"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildFeatures {
        prefab = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningPropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.media3:media3-common:1.10.1")
    implementation("androidx.media3:media3-datasource:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("commons-net:commons-net:3.13.0")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.61")
    testImplementation("junit:junit:4.13.2")
}
