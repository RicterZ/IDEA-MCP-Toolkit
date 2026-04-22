package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class FindInFilesArgs(
    val pattern: String = "",
    val filePattern: String = "",   // e.g. "*.kt", "*.java", "" means all
    val isRegex: Boolean = false,
    val ignoreCase: Boolean = true,
    val maxResults: Int = 50,
    val includeLibraries: Boolean = false  // search .class files in dependency JARs
)

class FindInFilesTool : AbstractMcpTool<FindInFilesArgs>(FindInFilesArgs.serializer()) {

    override val name = "find_in_files"
    override val description = """
        Searches for a text pattern inside project source files (like Cmd+Shift+F in IDEA).
        Returns matching file paths, line numbers, and the matching line content.
        Supports plain text and regex search.
        Parameters:
          - pattern: the text or regex to search for (required)
          - filePattern: glob to filter files, e.g. "*.kt", "*.java" (optional, default: all files)
          - isRegex: treat pattern as a regular expression (default: false)
          - ignoreCase: case-insensitive search (default: true)
          - maxResults: maximum number of matches to return (default: 50); set to 0 for no limit
          - includeLibraries: also search .class files in dependency JARs (default: false).
            Searches raw bytecode for the keyword (constant pool contains UTF-8 strings for
            class names, method names, field names, and string literals). Returns matching
            file paths only (no line numbers). Very fast — no decompilation needed.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("pattern", buildJsonObject {
                put("type", "string")
                put("description", "Text or regex pattern to search for")
            })
            put("filePattern", buildJsonObject {
                put("type", "string")
                put("description", "Glob pattern to filter files, e.g. \"*.kt\", \"*.java\". Omit for all files.")
            })
            put("isRegex", buildJsonObject {
                put("type", "boolean")
                put("description", "Treat pattern as regular expression (default: false)")
            })
            put("ignoreCase", buildJsonObject {
                put("type", "boolean")
                put("description", "Case-insensitive search (default: true)")
            })
            put("maxResults", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of matches to return (default: 50); set to 0 for no limit")
            })
            put("includeLibraries", buildJsonObject {
                put("type", "boolean")
                put("description",
                    "Also search .class files in dependency JARs for the keyword (default: false). " +
                    "Searches raw bytecode — no decompilation needed. Returns file paths only.")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
    }

    override fun handle(project: Project, args: FindInFilesArgs): Response {
        if (args.pattern.isBlank()) {
            return Response(error = "pattern must not be empty")
        }

        val regex = try {
            if (args.isRegex) {
                if (args.ignoreCase) Regex(args.pattern, RegexOption.IGNORE_CASE)
                else Regex(args.pattern)
            } else {
                if (args.ignoreCase) Regex(Regex.escape(args.pattern), RegexOption.IGNORE_CASE)
                else Regex(Regex.escape(args.pattern))
            }
        } catch (e: Exception) {
            return Response(error = "Invalid regex pattern: ${e.message}")
        }

        val fileGlob = args.filePattern.trim()
        val fileRegex = if (fileGlob.isNotEmpty()) {
            Regex(fileGlob.replace(".", "\\.").replace("*", ".*").replace("?", "."))
        } else null

        val results = buildJsonArray {
            var count = 0
            val limit = if (args.maxResults <= 0) Int.MAX_VALUE else args.maxResults
            val projectFileIndex = ProjectFileIndex.getInstance(project)

            runReadAction {
                projectFileIndex.iterateContent { virtualFile: VirtualFile ->
                    if (count >= limit) return@iterateContent false
                    if (virtualFile.isDirectory) return@iterateContent true
                    if (!virtualFile.isValid) return@iterateContent true
                    if (fileRegex != null && !fileRegex.containsMatchIn(virtualFile.name)) return@iterateContent true

                    val text = try {
                        String(virtualFile.contentsToByteArray(), virtualFile.charset)
                    } catch (e: Exception) {
                        return@iterateContent true
                    }

                    val basePath = project.basePath ?: ""
                    val relativePath = if (virtualFile.path.startsWith(basePath)) {
                        virtualFile.path.removePrefix("$basePath/")
                    } else {
                        virtualFile.path
                    }

                    text.lines().forEachIndexed { index, line ->
                        if (count >= limit) return@forEachIndexed
                        if (regex.containsMatchIn(line)) {
                            add(buildJsonObject {
                                put("path", relativePath)
                                put("line", index + 1)
                                put("content", line.trim())
                            })
                            count++
                        }
                    }
                    true
                }
            }

            // ── Phase 2: Library .class files (raw bytecode keyword search) ──
            if (args.includeLibraries && count < limit) {
                val searchBytes = args.pattern.toByteArray(Charsets.UTF_8)

                runReadAction {
                    val classRoots = OrderEnumerator.orderEntries(project)
                        .withoutModuleSourceEntries()
                        .classesRoots

                    for (root in classRoots) {
                        if (count >= limit) break
                        VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                            if (count >= limit) return@iterateChildrenRecursively false
                            if (vf.isDirectory || !vf.name.endsWith(".class")) {
                                return@iterateChildrenRecursively true
                            }
                            if (fileRegex != null && !fileRegex.containsMatchIn(vf.name)) {
                                return@iterateChildrenRecursively true
                            }

                            try {
                                val bytes = vf.contentsToByteArray()
                                if (containsBytes(bytes, searchBytes, args.ignoreCase)) {
                                    add(buildJsonObject {
                                        put("path", vf.path)
                                        put("inLibrary", true)
                                    })
                                    count++
                                }
                            } catch (_: Exception) { }
                            true
                        }
                    }
                }
            }
        }

        return Response(status = results.toString())
    }

    /**
     * Check if [haystack] contains [needle] bytes.
     * When [ignoreCase] is true, converts both to lowercase ASCII before comparison.
     */
    private fun containsBytes(haystack: ByteArray, needle: ByteArray, ignoreCase: Boolean): Boolean {
        if (needle.isEmpty()) return true
        if (haystack.size < needle.size) return false

        if (ignoreCase) {
            val haystackStr = String(haystack, Charsets.ISO_8859_1).lowercase()
            val needleStr = String(needle, Charsets.ISO_8859_1).lowercase()
            return haystackStr.contains(needleStr)
        }

        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
