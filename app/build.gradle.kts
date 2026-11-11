plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pt.casa.daikinocr"
    compileSdk = 34

    defaultConfig {
        applicationId = "pt.casa.daikinocr"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        val webhook = System.getenv("WEBHOOK_URL") ?: ""
        buildConfigField("String", "WEBHOOK_URL", "\"$webhook\"")
    }


    // Assinatura fixa (necessária para atualizar por cima). Vem dos "secrets" do GitHub no CI.
    val ksFile = System.getenv("KEYSTORE_FILE")
    if (ksFile != null && file(ksFile).exists()) {
        signingConfigs {
            create("fixa") {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "daikin"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("fixa")?.let { signingConfig = it }
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
