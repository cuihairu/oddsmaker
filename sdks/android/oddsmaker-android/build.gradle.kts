plugins {
  id("com.android.library") version "8.5.1"
  id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
  namespace = "io.oddsmaker.android"
  compileSdk = 34

  defaultConfig {
    minSdk = 21
    aarMetadata { minCompileSdk = 21 }
    consumerProguardFiles("consumer-rules.pro")
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
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  testImplementation("junit:junit:4.13.2")
}
