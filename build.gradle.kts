import com.lanrhyme.micyou.build.CheckLocalizationTask
import org.gradle.api.GradleException

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
}

tasks.register<CheckLocalizationTask>("checkLocalization") {
    group = "verification"
    description = "Checks localization strings.xml files for missing, extra, or empty keys using en and zh as base locales."
    resDir.set(layout.projectDirectory.dir("composeApp/src/commonMain/composeResources"))
    baseLocale.set("")
    baseLocales.set(listOf("", "zh"))
}

tasks.register("installGitHooks") {
    group = "verification"
    description = "Configures Git to use repository hooks from .githooks."
    notCompatibleWithConfigurationCache("Uses external git process and mutable repository state.")

    doLast {
        val hooksDir = rootProject.file(".githooks")
        if (!hooksDir.exists()) {
            throw GradleException("Hooks directory not found: ${hooksDir.absolutePath}")
        }

        if (!rootProject.file(".git").exists()) {
            logger.warn("Skipping hooks installation: .git directory not found.")
            return@doLast
        }

        val process = ProcessBuilder("git", "config", "core.hooksPath", ".githooks")
            .directory(rootProject.projectDir)
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Failed to configure Git hooks path. Exit code: $exitCode")
        }

        val preCommit = hooksDir.resolve("pre-commit")
        if (preCommit.exists()) {
            preCommit.setExecutable(true)
        }

        logger.lifecycle("Git hooks path configured to .githooks")
    }
}