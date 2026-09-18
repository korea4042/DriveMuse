plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
if(file("google-services.json").exists()) apply(plugin="com.google.gms.google-services")
fun quoted(value: String) = "\"" + value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r") + "\""
android {
    namespace = "ai.drivemuse.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "ai.drivemuse.app"; minSdk = 33; targetSdk = 35; versionCode = 4; versionName = "0.4.0"
        buildConfigField("String", "YT_API_KEY", quoted(providers.gradleProperty("ytApiKey").orNull ?: ""))
        buildConfigField("String", "GEMINI_MODEL", quoted(providers.gradleProperty("geminiModel").orNull ?: ""))
        buildConfigField("String", "WEATHER_API_KEY", quoted(providers.gradleProperty("weatherApiKey").orNull ?: ""))
    }
    // Stable debug signature so one OAuth SHA-1 registration keeps working across CI and local builds.
    // drivemuse.keystore is a throwaway TEST key committed on purpose; never ship a release signed with it.
    val ksFile = rootProject.file("drivemuse.keystore")
    if (ksFile.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = ksFile
            // Fixed credentials: this is a throwaway debug key, and a blank/typo'd secret must not
            // silently fall back to the runner's random key (that would change the OAuth SHA-1).
            storePassword = "drivemuse"
            keyAlias = "drivemuse"
            keyPassword = "drivemuse"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}
// Room exportSchema=true (design §18): keep every schema version under app/schemas for migration tests.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20240303")
    implementation(project(":core:domain")); implementation(project(":core:designsystem"))
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.7.2"); implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
}
