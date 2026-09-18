plugins { id("com.android.library"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "ai.drivemuse.designsystem"; compileSdk = 36
    defaultConfig { minSdk = 33 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    api("androidx.compose.material3:material3:1.4.0")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.ui:ui")
}
