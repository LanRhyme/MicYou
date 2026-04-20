package com.lanrhyme.micyou.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class CheckLocalizationTask : DefaultTask() {

    @get:InputDirectory
    abstract val i18nDir: DirectoryProperty

    @get:Input
    abstract val baseLocale: Property<String>

    @get:Input
    abstract val baseLocales: ListProperty<String>

    @TaskAction
    fun run() {
        val directory = i18nDir.get().asFile
        val files = directory
            .listFiles { file -> file.isFile && file.name.startsWith("strings_") && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()

        if (files.isEmpty()) {
            throw GradleException("No localization files found under: ${directory.absolutePath}")
        }

        val localeToFile = files.associateBy { localeFromFile(it) }
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
                    "Base locale file strings_${base}.json was not found. Available locales: ${localeToFile.keys.sorted().joinToString(", ")}",
                )
            }
        }

        val localeMaps = localeToFile.mapValues { (_, file) -> parseJson(file) }
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
            issues += "[${primaryBaseFile.name}] Empty values in base locale (${emptyPrimaryBaseKeys.size}): ${emptyPrimaryBaseKeys.joinToString(", ")}"
        }

        for (base in bases.drop(1)) {
            val baseFile = localeToFile.getValue(base)
            val baseMap = localeMaps.getValue(base)
            val missingInBase = (baseKeyUnion - baseMap.keys).sorted()
            val emptyInBase = baseMap.filterValues { it.isBlank() }.keys.sorted()

            if (missingInBase.isNotEmpty()) {
                issues += "[${baseFile.name}] Missing keys required by base locales (${missingInBase.size}): ${missingInBase.joinToString(", ")}" 
            }
            if (emptyInBase.isNotEmpty()) {
                issues += "[${baseFile.name}] Empty values in base locale (${emptyInBase.size}): ${emptyInBase.joinToString(", ")}" 
            }
        }

        val missingInPrimaryBase = (baseKeyUnion - primaryBaseMap.keys).sorted()
        if (missingInPrimaryBase.isNotEmpty()) {
            issues += "[${primaryBaseFile.name}] Missing keys required by base locales (${missingInPrimaryBase.size}): ${missingInPrimaryBase.joinToString(", ")}" 
        }

        for ((locale, map) in localeMaps) {
            if (locale in bases) continue

            val file = localeToFile.getValue(locale)
            val missing = (baseKeyUnion - map.keys).sorted()
            val extra = (map.keys - baseKeyUnion).sorted()
            val emptyValues = map.filterValues { it.isBlank() }.keys.sorted()

            if (missing.isNotEmpty()) {
                issues += "[${file.name}] Missing keys (${missing.size}): ${missing.joinToString(", ")}" 
            }
            if (extra.isNotEmpty()) {
                issues += "[${file.name}] Extra keys (${extra.size}): ${extra.joinToString(", ")}" 
            }
            if (emptyValues.isNotEmpty()) {
                issues += "[${file.name}] Empty values (${emptyValues.size}): ${emptyValues.joinToString(", ")}" 
            }
        }

        if (issues.isNotEmpty()) {
            logger.error("Localization check failed:")
            issues.forEach { logger.error(" - $it") }
            throw GradleException("Localization check failed with ${issues.size} issue(s).")
        }

        logger.lifecycle(
            "Localization check passed. Files: ${files.size}, base locales: ${bases.joinToString(",")}, keys: ${baseKeyUnion.size}.",
        )
    }

    private fun localeFromFile(file: File): String =
        file.name.removePrefix("strings_").removeSuffix(".json")

    private fun parseJson(file: File): Map<String, String> {
        val root = try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            throw GradleException("Failed to parse JSON in ${file.name}: ${e.message}", e)
        }

        val result = linkedMapOf<String, String>()
        flatten(root, "", file.name, result)
        return result
    }

    private fun flatten(node: JsonElement, prefix: String, fileName: String, out: MutableMap<String, String>) {
        when (node) {
            is JsonObject -> {
                for ((key, value) in node) {
                    val nextPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
                    flatten(value, nextPrefix, fileName, out)
                }
            }

            is JsonArray -> {
                throw GradleException("Value of key '$prefix' in $fileName is a JSON array. Arrays are not supported in localization files.")
            }

            is JsonPrimitive -> {
                if (!node.isString) {
                    throw GradleException("Value of key '$prefix' in $fileName must be a JSON string.")
                }
                out[prefix] = node.content
            }

            else -> {
                throw GradleException("Unsupported JSON value at key '$prefix' in $fileName.")
            }
        }
    }
}
