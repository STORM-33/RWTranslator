package com.example.rwtranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.logging.Logger
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.regex.Pattern
import kotlinx.coroutines.flow.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

class FileProcessor(private val translator: Translator) {

    private val curlyBracesPattern = Regex("""([$%]\{(?:[^{}]|\{(?:[^{}]|\{[^{}]*\})*\})*\})|(\\[nN])""")
    private val skipTranslationPattern = Regex("""^(i:)?[a-zA-Z0-9_.-]+(\.[a-zA-Z0-9_.-]+)+$""")
    private val commentPattern = Regex("""^\s*#""")
    private val logger = Logger.getLogger("FileProcessor")

    private val fieldsToTranslate = listOf(
        "displayText", "displayDescription", "text", "description", "isLockedMessage", "isLockedAltMessage",
        "isLockedAlt2Message", "showMessageToPlayer", "showMessageToAllPlayers", "showMessageToAllEnemyPlayers",
        "showQuickWarLogToPlayer", "showQuickWarLogToAllPlayers", "displayName", "displayNameShort", "title"
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun processAllFiles(
        inputStream: InputStream,
        outputStream: OutputStream,
        src: String,
        dest: String,
        mode: String,
        progressCallback: (Int, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tempDir = createTempDirectory()
        val processDir = File(tempDir, "work_dir")
        processDir.mkdirs()

        try {
            extractZipFile(inputStream, processDir)

            val totalFiles = processDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("ini", "template", "txt") }
                .count()

            var processedFiles = 0

            processDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("ini", "template", "txt") }
                .asFlow()
                .flatMapMerge(Runtime.getRuntime().availableProcessors()) { file ->
                    flow {
                        processFile(file, src, dest, mode)
                        processedFiles++
                        withContext(Dispatchers.Main) {
                            progressCallback(processedFiles, totalFiles)
                        }
                        emit(Unit)
                    }
                }
                .collect()

            createNewZipFile(processDir, outputStream)
        } finally {
            tempDir.deleteRecursively()
        }
    }


    private suspend fun processFile(file: File, src: String, dest: String, mode: String) {
        logger.info("Started processing file: ${file.path}")

        val lines = withContext(Dispatchers.IO) {
            file.readLines()
        }

        val newLines = mutableListOf<String>()
        var coreNameFieldIndex: Int? = null
        var coreNameValue: String? = null
        var inCoreSection = false
        var coreDisplayTextFound = false
        var coreDisplayDescriptionFound = false

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            if (commentPattern.matches(line)) {
                newLines.add(lines[i])
                i++
                continue
            }

            if ("[core]" in line) {
                inCoreSection = true
            } else if (line.startsWith("[") && line.endsWith("]") && "core" !in line) {
                inCoreSection = false
            }

            if ((line.startsWith("name:") || line.startsWith("name :")) && inCoreSection) {
                val (_, value) = line.split(':', limit = 2)
                coreNameValue = value.trim()
                coreNameFieldIndex = newLines.size
            }

            if (line.startsWith("displayText:") && inCoreSection) {
                coreDisplayTextFound = true
            }

            if (line.startsWith("displayDescription:") && inCoreSection) {
                coreDisplayDescriptionFound = true
            }

            var fieldFound = false
            for (field in fieldsToTranslate) {
                if (line.startsWith("$field:")) {
                    val (fieldName, initialValue) = line.split(':', limit = 2)
                    var cleanValue = initialValue.trim()
                    var quoteType = ""

                    logger.info("Processing field $fieldName with initial value: $cleanValue at line $i")

                    // Обработка многострочных значений
                    if ("\"\"\"" in cleanValue || "'''" in cleanValue) {
                        quoteType = if ("\"\"\"" in cleanValue) "\"\"\"" else "'''"
                        val multilineValue = mutableListOf(cleanValue)

                        if (cleanValue.count { it.toString() == quoteType } <= 1) {
                            i++

                            while (i < lines.size && quoteType !in lines[i]) {
                                multilineValue.add(lines[i].trimEnd('\n'))
                                i++
                            }
                            if (i < lines.size) {
                                multilineValue.add(lines[i].trimEnd('\n'))
                            }
                        }
                        cleanValue = multilineValue.joinToString("\n")
                        cleanValue = cleanValue.replace("\"\"\"", "").replace("'''", "").trim()
                    }

                    // Удаляем окружающие кавычки для перевода
                    val valueToTranslate = cleanValue.removeSurrounding("\"\"\"").removeSurrounding("'''").trim()

                    if (skipTranslationPattern.find(valueToTranslate) != null) {
                        newLines.add(lines[i])
                        logger.info("Skipping translation for field: $fieldName due to skip pattern at line $i")
                        fieldFound = true
                        break
                    }

                    val parts = splitTextForTranslation(valueToTranslate)
                    val translatedParts = mutableListOf<String>()

                    for (part in parts) {
                        if (curlyBracesPattern.matches(part)) {
                            translatedParts.add(part)
                        } else {
                            val translatedPart = translator.translate(part, src, dest)
                            translatedParts.add(translatedPart)
                        }
                    }

                    val translatedText = translatedParts.joinToString("")

                    when (mode) {
                        "add" -> {
                            newLines.add("$fieldName: $quoteType$cleanValue$quoteType")
                            newLines.add("${fieldName}_$dest: $quoteType$translatedText$quoteType")
                        }
                        "replace" -> {
                            newLines.add("$fieldName: $quoteType$translatedText$quoteType")
                            newLines.add("${fieldName}_$src: $quoteType$cleanValue$quoteType")
                        }
                    }

                    logger.info("Processed field $fieldName: Original: $cleanValue, Translated: $translatedText")

                    fieldFound = true
                    break
                }
            }

            if (!fieldFound) {
                newLines.add(lines[i])
            }

            i++
        }

        val fieldsToProcess = mapOf(
            "displayDescription" to !coreDisplayDescriptionFound,
            "displayText" to !coreDisplayTextFound
        )

        for ((fieldType, notFound) in fieldsToProcess) {
            if (notFound && coreNameFieldIndex != null && coreNameValue != null) {
                try {
                    val translatedText = translator.translate(coreNameValue.removeSurrounding("\"\"\"").removeSurrounding("'''").trim(), src, dest)
                    if (!newLines[coreNameFieldIndex].endsWith('\n')) {
                        newLines[coreNameFieldIndex] = newLines[coreNameFieldIndex] + "\n"
                    }

                    when (mode) {
                        "add" -> {
                            newLines.add(coreNameFieldIndex + 1, "$fieldType: $coreNameValue")
                            newLines.add(coreNameFieldIndex + 2, "${fieldType}_$dest: $translatedText")
                        }
                        "replace" -> {
                            newLines.add(coreNameFieldIndex + 1, "$fieldType: $translatedText")
                            newLines.add(coreNameFieldIndex + 2, "${fieldType}_$src: $coreNameValue")
                        }
                    }

                    logger.info("Translated core $fieldType: Original: $coreNameValue, Translated: $translatedText")
                } catch (e: Exception) {
                    logger.severe("Translation error in ${file.path} - $fieldType: ${e.message}")
                }
            }
        }

        withContext(Dispatchers.IO) {
            file.writeText(newLines.joinToString("\n"))
        }

        logger.info("Finished processing file: ${file.path}")
    }

    private fun splitTextForTranslation(text: String): List<String> {

        val combinedPattern = """([$%]\{(?:[^{}]|\{(?:[^{}]|\{[^{}]*\})*\})*\})|(\\[nN])"""
        val regex = Pattern.compile(combinedPattern)

        val matcher = regex.matcher(text)
        val result = mutableListOf<String>()
        var lastEnd = 0

        while (matcher.find()) {
            val start = matcher.start()
            if (start > lastEnd) {
                result.add(text.substring(lastEnd, start))
            }
            result.add(matcher.group())
            lastEnd = matcher.end()
        }

        if (lastEnd < text.length) {
            result.add(text.substring(lastEnd))
        }

        // Удаляем пустые строки и объединяем последовательные текстовые части
        return result.filter { it.isNotEmpty() && it.isNotBlank() }
            .fold(mutableListOf<String>()) { acc, part ->
                if (acc.isNotEmpty() && !regex.matcher(part).matches() && !regex.matcher(acc.last()).matches()) {
                    acc[acc.lastIndex] += part
                } else {
                    acc.add(part)
                }
                acc
            }
    }

    private fun extractZipFile(inputStream: InputStream, destDir: File) {
        ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
            generateSequence { zipIn.nextEntry }
                .forEach { entry ->
                    val file = destDir.resolve(entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().buffered().use { output ->
                            zipIn.copyTo(output)
                        }
                    }
                }
        }
    }

    private fun createNewZipFile(sourceDir: File, outputStream: OutputStream) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = file.toRelativeString(sourceDir)
                    zipOut.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered().use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
        }
    }

    private fun createTempDirectory(): File {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "RWTranslator_" + System.currentTimeMillis())
        if (!tempDir.mkdir()) {
            throw IOException("Failed to create temp directory")
        }
        tempDir.deleteOnExit()
        return tempDir
    }
}