import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Play upload key (signing/, kept out of version control). Without it, release builds fall back to the debug key.
val uploadKey = rootProject.file("signing/keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
val ciKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "com.oneuiclone.home"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.launchii"
        minSdk = 33
        targetSdk = 36
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
    }

    signingConfigs {
        if (ciKeystore != null) create("ci") {
            storeFile = file(ciKeystore)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
        }
        if (uploadKey != null) create("upload") {
            storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
            storePassword = uploadKey.getProperty("storePassword")
            keyAlias = uploadKey.getProperty("keyAlias")
            keyPassword = uploadKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ci")
                ?: signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    // widget picker: a recycled list stays smooth with hundreds of widgets
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // installs src/main/baseline-prof.txt for sideloaded APKs (no Play cloud profiles)
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
}
