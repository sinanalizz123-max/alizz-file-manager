plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace="com.alizz.filemanager"
 compileSdk=35
  defaultConfig { applicationId="com.alizz.filemanager"; minSdk=26; targetSdk=35; versionCode=3; versionName="1.0.0" }
  buildFeatures { compose=true; aidl=true }
 kotlinOptions { jvmTarget="17" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
}
dependencies {
 val bom=platform("androidx.compose:compose-bom:2024.12.01")
 implementation(bom)
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("io.coil-kt:coil-compose:2.6.0")
  implementation("androidx.exifinterface:exifinterface:1.3.7")
  implementation("androidx.media3:media3-exoplayer:1.5.1")
  implementation("androidx.media3:media3-ui:1.5.1")
  implementation("org.apache.commons:commons-compress:1.26.2")
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation("commons-net:commons-net:3.11.1")
  implementation("com.hierynomus:sshj:0.38.0")
  implementation("com.hierynomus:smbj:0.14.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.android.gms:play-services-auth:21.2.0")
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 debugImplementation("androidx.compose.ui:ui-tooling")
}