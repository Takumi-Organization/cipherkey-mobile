plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = "0.1.0"
val (vMajor, vMinor, vPatch) = appVersion.split(".").map { it.toInt() }
val coreDir = rootProject.file("core") // copia de las fuentes del módulo compartido (ver core/README.md)

android {
    namespace = "dev.cipherkey.mobile"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "dev.cipherkey.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = vMajor * 10_000 + vMinor * 100 + vPatch
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    sourceSets {
        // Los vectores de prueba del módulo compartido (p. ej. un .ckey v2 real) también se prueban en Android.
        getByName("androidTest").resources.srcDir(File(coreDir, "src/test/resources"))
        getByName("test").resources.srcDir(File(coreDir, "src/test/resources"))
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += "PropertyEscape" // falso positivo: \u00f1 es un escape Unicode válido en .properties
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    // biometric arrastra fragment 1.2.5, que rompe los selectores de archivos (requestCode de 16 bits): se fija uno actual
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core"))

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
