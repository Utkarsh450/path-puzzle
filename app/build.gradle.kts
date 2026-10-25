import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Upload key for Play (signing/keystore.properties, kept out of version control and backed up by hand).
val keystoreProps = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Real AdMob ids live in signing/ads.properties (admobAppId, rewardedUnit, interstitialUnit).
// Debug builds and a release without that file use Google's sample ids, which show test ads only.
val adProps = Properties().apply {
    val f = rootProject.file("signing/ads.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val testAppId = "ca-app-pub-3940256099942544~3347511713"
val testRewarded = "ca-app-pub-3940256099942544/5224354917"
val testInterstitial = "ca-app-pub-3940256099942544/1033173712"
// -PtestAds=true builds a release with Google's sample ads even if signing/ads.properties exists
// (for testers: a real ad unit must never be clickable by anyone but real players).
val forceTestAds = project.hasProperty("testAds")
fun adId(key: String, sample: String) = if (forceTestAds) sample else adProps.getProperty(key, sample)

android {
    namespace = "com.arrowpuzzle.game"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.utkarsh.pathpuzzle"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0"
        // The platform's own JUnit 3 runner: instrumented tests need no AndroidX libraries.
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"

        ndk {
            // The test phone is a 32-bit armeabi-v7a device, so keep every common ABI.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = testAppId
            buildConfigField("String", "REWARDED_UNIT", "\"$testRewarded\"")
            buildConfigField("String", "INTERSTITIAL_UNIT", "\"$testInterstitial\"")
            buildConfigField("boolean", "TEST_ADS", "true")
        }
        release {
            // R8 shrinks the ads library (most of the code) away from the parts the game uses: a smaller
            // download and a faster start. The native entry points are kept by proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val appId = adId("admobAppId", testAppId)
            manifestPlaceholders["admobAppId"] = appId
            buildConfigField("String", "REWARDED_UNIT", "\"${adId("rewardedUnit", testRewarded)}\"")
            buildConfigField("String", "INTERSTITIAL_UNIT", "\"${adId("interstitialUnit", testInterstitial)}\"")
            buildConfigField("boolean", "TEST_ADS", (appId == testAppId).toString())
            signingConfig = signingConfigs.findByName("release")
            // native symbols let Play Console symbolicate crashes from the C++ core
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    // JVM unit tests touch android.graphics.Paint (particles); stubs return defaults instead of throwing.
    testOptions.unitTests.isReturnDefaultValues = true

    useLibrary("android.test.runner")
    useLibrary("android.test.base")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
