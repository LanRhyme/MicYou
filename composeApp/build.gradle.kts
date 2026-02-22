import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import javax.imageio.ImageIO
import javax.inject.Inject

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
        // 强制使用dorkbox/OS 1.11版本以修复60秒阻塞问题
        if (requested.group == "com.dorkbox" && requested.name == "OS") {
            useVersion("1.11")
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
            mainClass.set("com.lanrhyme.micyou.MainKt")
        }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
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
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
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
            implementation(libs.ktor.client.java)
            implementation("de.maxhenkel.rnnoise4j:rnnoise4j:2.1.2")
            implementation("io.ultreia:bluecove:2.1.1")
            implementation(libs.systemtray)
        }
    }
}

android {
    namespace = "com.lanrhyme.micyou"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lanrhyme.micyou"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = project.property("project.version.code").toString().toInt()
        versionName = project.property("project.version").toString()
    }

    buildFeatures {
        buildConfig = true
    }
    
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            
            if (!keystorePath.isNullOrEmpty() && !keystorePassword.isNullOrEmpty()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.lanrhyme.micyou.MainKt"
        jvmArgs("-Dfile.encoding=UTF-8", "-Dapp.version=${project.property("project.version")}")

        nativeDistributions {
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            targetFormats(TargetFormat.Dmg, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = project.property("project.name").toString()
            packageVersion = project.property("project.version").toString().substringBefore("-").substringBefore("hotfix")
            description = "MicYou Application"
            vendor = "LanRhyme"
            copyright = "Copyright (c) 2026 LanRhyme"
            modules("java.net.http")
            
            windows {
                val generatedIcon = layout.buildDirectory.file("generated/icons/icon256.ico").get().asFile
                iconFile.set(generatedIcon)
                perUserInstall = true
                menu = true
                menuGroup = "MicYou"
                shortcut = true
                dirChooser = false
                upgradeUuid = "f76264ff-05a4-494a-a8fb-7ed3410cb17c"
                console = false
            }
            linux {
        		iconFile.set(project.file("src/commonMain/composeResources/drawable/app_icon.png"))
    		}

            macOS {
                iconFile.set(project.file("src/commonMain/composeResources/drawable/mac_icon.icns"))
                infoPlist {
                    extraKeysRawXml = """    <key>CFBundleIconName</key>
    <string>MicYou</string>"""
                }
            }
        }
    }
}

val windowsIconIcoFile = layout.buildDirectory.file("generated/icons/icon256.ico").get().asFile
abstract class GenerateWindowsIconIcoTask : DefaultTask() {
    @get:InputFile val sourcePng: RegularFileProperty = project.objects.fileProperty()
    @get:OutputFile val outputIco: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun run() {
        val sourceFile = sourcePng.get().asFile
        val outFile = outputIco.get().asFile

        if (!sourceFile.exists()) {
            throw GradleException("Icon source not found: ${sourceFile.absolutePath}")
        }

        val src = ImageIO.read(sourceFile) ?: throw GradleException("Unable to read png: ${sourceFile.absolutePath}")
        val target = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, 256, 256, null)
        } finally {
            g.dispose()
        }

        val pngBytes = ByteArrayOutputStream().use { baos ->
            if (!ImageIO.write(target, "png", baos)) {
                throw GradleException("Unable to encode png for ico")
            }
            baos.toByteArray()
        }

        outFile.parentFile.mkdirs()
        FileOutputStream(outFile).use { fos ->
            DataOutputStream(fos).use { out ->
                fun writeShortLe(v: Int) {
                    out.writeByte(v and 0xFF)
                    out.writeByte((v ushr 8) and 0xFF)
                }
                fun writeIntLe(v: Int) {
                    out.writeByte(v and 0xFF)
                    out.writeByte((v ushr 8) and 0xFF)
                    out.writeByte((v ushr 16) and 0xFF)
                    out.writeByte((v ushr 24) and 0xFF)
                }

                writeShortLe(0)
                writeShortLe(1)
                writeShortLe(1)

                out.writeByte(0)
                out.writeByte(0)
                out.writeByte(0)
                out.writeByte(0)
                writeShortLe(1)
                writeShortLe(32)
                writeIntLe(pngBytes.size)
                writeIntLe(6 + 16)

                out.write(pngBytes)
            }
        }
    }
}

tasks.register<GenerateWindowsIconIcoTask>("generateWindowsIconIco") {
    sourcePng.set(layout.projectDirectory.file("src/commonMain/composeResources/drawable/app_icon.png"))
    outputIco.set(layout.buildDirectory.file("generated/icons/icon256.ico"))
}

tasks.matching { it.name in setOf("createDistributable", "createReleaseDistributable", "packageExe", "packageReleaseExe", "packageWindowsNsis") }
    .configureEach { dependsOn("generateWindowsIconIco") }

// Windows 打包后复制托盘图标 (32x32) 到应用目录
// 256x256 的图标在 Windows 托盘中无法正常显示，需要使用小尺寸图标
val copyTrayIcon by tasks.registering(Copy::class) {
    from("src/commonMain/composeResources/drawable/icon32.ico")
    into(layout.buildDirectory.dir("compose/binaries/main/app/${project.property("project.name")}"))
}

// 复制 Assets.car 到 macOS app bundle（支持液态玻璃图标）
val copyAssetsCar by tasks.registering(Copy::class) {
    from("resources/macos/Assets.car")
    into(layout.buildDirectory.dir("compose/binaries/main/app/${project.property("project.name")}.app/Contents/Resources"))
}

// Windows/macOS 打包时执行 copyTrayIcon
tasks.matching { it.name in setOf("createDistributable", "createReleaseDistributable") }
    .configureEach {
        dependsOn(copyTrayIcon)
    }

// macOS DMG 打包
tasks.matching { it.name.startsWith("packageDmg") }
    .configureEach {
        dependsOn(copyTrayIcon)
        dependsOn("createDistributable")
        dependsOn(copyAssetsCar)
    }

tasks.matching { it.name == "copyAssetsCar" }
    .configureEach {
        mustRunAfter("createDistributable")
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

tasks.register<Zip>("packageWindowsZip") {
    dependsOn("createDistributable", copyTrayIcon)

    val version = project.property("project.version").toString()
    val distDir = layout.buildDirectory.dir("compose/binaries/main/app")

    from(distDir)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveBaseName.set("${project.property("project.name")}-windows")
    archiveVersion.set(version)
}

abstract class PackageWindowsNsisTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input val appName: Property<String> = project.objects.property(String::class.java)
    @get:Input val appVersion: Property<String> = project.objects.property(String::class.java)
    @get:Input val vendor: Property<String> = project.objects.property(String::class.java)

    @get:InputDirectory val inputDir: DirectoryProperty = project.objects.directoryProperty()
    @get:InputFile val scriptFile: RegularFileProperty = project.objects.fileProperty()
    @get:OutputFile val outputFile: RegularFileProperty = project.objects.fileProperty()

    @get:Input
    @get:Optional
    val makensisPath: Property<String> = project.objects.property(String::class.java)

    @get:Input
    @get:Optional
    val iconPath: Property<String> = project.objects.property(String::class.java)

    @TaskAction
    fun run() {
        val script = scriptFile.get().asFile
        val input = inputDir.get().asFile
        val out = outputFile.get().asFile

        if (!script.exists()) {
            throw GradleException("NSIS script not found: ${script.absolutePath}")
        }
        if (!input.exists()) {
            throw GradleException("Distributable not found: ${input.absolutePath}")
        }

        out.parentFile.mkdirs()

        val makensisExe = makensisPath.orNull?.takeIf { it.isNotBlank() } ?: run {
            val candidates = listOf(
                File("C:/Program Files (x86)/NSIS/makensis.exe"),
                File("C:/Program Files/NSIS/makensis.exe"),
            )
            candidates.firstOrNull { it.exists() }?.absolutePath ?: "makensis"
        }

        val iconArg = iconPath.orNull?.takeIf { it.isNotBlank() }?.let { "/DICON_FILE=$it" } ?: "/DICON_FILE="

        try {
            execOperations.exec {
                executable = makensisExe
                args(
                    "/DAPP_NAME=${appName.get()}",
                    "/DAPP_VERSION=${appVersion.get()}",
                    "/DVENDOR=${vendor.get()}",
                    "/DINPUT_DIR=${input.absolutePath}",
                    "/DOUTPUT_DIR=${out.parentFile.absolutePath}",
                    iconArg,
                    script.absolutePath,
                )
            }
        } catch (e: Exception) {
            throw GradleException(
                "Failed to run makensis. Install NSIS and ensure makensis is available, or set -Pnsis.makensis=<path-to-makensis.exe>.",
                e,
            )
        }

        if (!out.exists()) {
            throw GradleException("NSIS installer was not created: ${out.absolutePath}")
        }
    }
}

tasks.register<PackageWindowsNsisTask>("packageWindowsNsis") {
    dependsOn("createDistributable", copyTrayIcon)

    val appNameValue = project.property("project.name").toString()
    val versionValue = project.property("project.version").toString()

    appName.set(appNameValue)
    appVersion.set(versionValue)
    vendor.set("LanRhyme")

    inputDir.set(layout.buildDirectory.dir("compose/binaries/main/app/$appNameValue"))
    scriptFile.set(layout.projectDirectory.file("nsis/installer.nsi"))
    outputFile.set(layout.buildDirectory.file("distributions/$appNameValue-$versionValue-setup-nsis.exe"))

    makensisPath.set(
        providers.gradleProperty("nsis.makensis")
            .orElse(providers.environmentVariable("NSIS_MAKENSIS"))
            .orElse("")
    )

    iconPath.set(windowsIconIcoFile.absolutePath)
}
