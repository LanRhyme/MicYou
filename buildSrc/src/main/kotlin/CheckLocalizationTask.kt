package com.lanrhyme.micyou.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

abstract class CheckLocalizationTask : DefaultTask() {

    @get:InputDirectory
    abstract val resDir: DirectoryProperty

    @get:Input
    abstract val baseLocale: Property<String>

    @get:Input
    abstract val baseLocales: ListProperty<String>

    @TaskAction
    fun run() {
        val directory = resDir.get().asFile
        val stringsFiles = directory.listFiles { file ->
            file.isDirectory && file.name.startsWith("values") && File(file, "strings.xml").exists()
        }?.map { dir ->
            val locale = if (dir.name == "values") "" else dir.name.removePrefix("values-")
            locale to File(dir, "strings.xml")
        }?.sortedBy { it.first }
            .orEmpty()

        if (stringsFiles.isEmpty()) {
            throw GradleException("No strings.xml files found under: ${directory.absolutePath}")
        }

        val localeToFile = stringsFiles.toMap()
        val configuredBaseLocales = baseLocales.orNull
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val bases = if (configuredBaseLocales.isNotEmpty()) {
            configuredBaseLocales.distinct()
        } else {
            listOf(baseLocale.get().trim())
        }
        if (bases.isEmpty()) {
            throw GradleException("No base locales configured.")
        }

        for (base in bases) {
            if (!localeToFile.containsKey(base)) {
                throw GradleException(
                    "Base locale 'values${if (base.isEmpty()) "" else "-$base"}/strings.xml' not found. Available: ${localeToFile.keys.sorted().joinToString(", ") { if (it.isEmpty()) "values" else "values-$it" }}"
                )
            }
        }

        val localeMaps = localeToFile.mapValues { (_, file) -> parseStringsXml(file) }
        val primaryBase = bases.first()
        val primaryBaseFile = localeToFile.getValue(primaryBase)
        val primaryBaseMap = localeMaps.getValue(primaryBase)
        val baseKeyUnion = bases
            .asSequence()
            .flatMap { localeMaps.getValue(it).keys.asSequence() }
            .toSet()
        val issues = mutableListOf<String>()

        val emptyPrimaryBaseKeys = primaryBaseMap.filterValues { it.isBlank() }.keys.sorted()
        if (emptyPrimaryBaseKeys.isNotEmpty()) {
            issues += "[${primaryBaseFile.parentFile.name}/strings.xml] Empty values in base locale (${emptyPrimaryBaseKeys.size}): ${emptyPrimaryBaseKeys.joinToString(", ")}"
        }

        for (base in bases.drop(1)) {
            val baseFile = localeToFile.getValue(base)
            val baseMap = localeMaps.getValue(base)
            val missingInBase = (baseKeyUnion - baseMap.keys).sorted()
            val emptyInBase = baseMap.filterValues { it.isBlank() }.keys.sorted()

            if (missingInBase.isNotEmpty()) {
                issues += "[${baseFile.parentFile.name}/strings.xml] Missing keys required by base locales (${missingInBase.size}): ${missingInBase.joinToString(", ")}"
            }
            if (emptyInBase.isNotEmpty()) {
                issues += "[${baseFile.parentFile.name}/strings.xml] Empty values in base locale (${emptyInBase.size}): ${emptyInBase.joinToString(", ")}"
            }
        }

        val missingInPrimaryBase = (baseKeyUnion - primaryBaseMap.keys).sorted()
        if (missingInPrimaryBase.isNotEmpty()) {
            issues += "[${primaryBaseFile.parentFile.name}/strings.xml] Missing keys required by base locales (${missingInPrimaryBase.size}): ${missingInPrimaryBase.joinToString(", ")}"
        }

        for ((locale, map) in localeMaps) {
            if (locale in bases) continue

            val file = localeToFile.getValue(locale)
            val missing = (baseKeyUnion - map.keys).sorted()
            val extra = (map.keys - baseKeyUnion).sorted()
            val emptyValues = map.filterValues { it.isBlank() }.keys.sorted()

            if (missing.isNotEmpty()) {
                issues += "[${file.parentFile.name}/strings.xml] Missing keys (${missing.size}): ${missing.joinToString(", ")}"
            }
            if (extra.isNotEmpty()) {
                issues += "[${file.parentFile.name}/strings.xml] Extra keys (${extra.size}): ${extra.joinToString(", ")}"
            }
            if (emptyValues.isNotEmpty()) {
                issues += "[${file.parentFile.name}/strings.xml] Empty values (${emptyValues.size}): ${emptyValues.joinToString(", ")}"
            }
        }

        if (issues.isNotEmpty()) {
            logger.error("Localization check failed:")
            issues.forEach { logger.error(" - $it") }
            throw GradleException("Localization check failed with ${issues.size} issue(s).")
        }

        logger.lifecycle(
            "Localization check passed. Locales: ${stringsFiles.size}, base: ${bases.joinToString(",")}, keys: ${baseKeyUnion.size}."
        )
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        try {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val node = strings.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                val value = node.textContent ?: ""
                result[name] = value
            }
        } catch (e: Exception) {
            throw GradleException("Failed to parse ${file.parentFile.name}/strings.xml: ${e.message}", e)
        }
        return result
    }
}
