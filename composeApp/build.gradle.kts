import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val composeVersion = libs.versions.composeMultiplatform.get()
val skikoVersion = "0.8.18"

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.compose" || requested.group.startsWith("org.jetbrains.compose.")) {
            useVersion(composeVersion)
        }
        if (requested.group == "org.jetbrains.skiko") {
            useVersion(skikoVersion)
        }
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    jvm {
        mainRun {
            mainClass.set("com.lanrhyme.androidmic_md.MainKt")
        }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.ktor.network)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.compose.material.icons.extended)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.kotlinx.serialization.protobuf)
        }
    }
}

android {
    namespace = "com.lanrhyme.androidmic_md"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lanrhyme.androidmic_md"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.lanrhyme.androidmic_md.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.lanrhyme.androidmic_md"
            packageVersion = "1.0.0"
        }
    }
}
tasks.matching { it.name == "jvmRun" }.configureEach {
    if (this is org.gradle.process.JavaForkOptions) {
        val tmpDir = layout.buildDirectory.dir("tmp/jvmRun").get().asFile
        doFirst {
            tmpDir.mkdirs()
        }
        jvmArgs("-Djava.io.tmpdir=${tmpDir.absolutePath}")
    }
}
