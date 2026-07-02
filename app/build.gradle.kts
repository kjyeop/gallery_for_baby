plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kjyeop.babygallery"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kjyeop.babygallery"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        (variant as? com.android.build.api.variant.HasDeviceTestsBuilder)
            ?.deviceTests
            ?.values
            ?.forEach { it.enable = false }
        (variant as? com.android.build.api.variant.HasHostTestsBuilder)
            ?.hostTests
            ?.values
            ?.forEach { it.enable = false }
        (variant as? com.android.build.api.variant.HasTestSuitesBuilder)
            ?.suites
            ?.values
            ?.forEach { it.enable = false }
        (variant as? com.android.build.api.variant.HasUnitTestBuilder)?.enableUnitTest = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
