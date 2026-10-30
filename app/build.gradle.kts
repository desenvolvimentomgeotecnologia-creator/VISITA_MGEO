plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val senhaChave = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "mgeo-visitas"
val numeroBuild = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "br.com.mgeo.visitas"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.mgeo.visitas"
        minSdk = 26
        targetSdk = 35
        versionCode = numeroBuild
        versionName = "1.0.$numeroBuild"
    }

    // Chave fixa da equipe: assim cada APK novo instala por cima do antigo sem apagar os relatórios.
    signingConfigs {
        create("equipe") {
            storeFile = file("equipe.keystore")
            storePassword = senhaChave
            keyAlias = "mgeo"
            keyPassword = senhaChave
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("equipe")
        }
        debug {
            signingConfig = signingConfigs.getByName("equipe")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Reconhecimento de voz offline
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
