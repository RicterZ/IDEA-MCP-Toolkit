package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class GetSymbolsOverviewArgs(
    val path: String = "",          // relative or absolute path to the file
    val depth: Int = 1              // 0 = top-level only, 1 = include members
)

class GetSymbolsOverviewTool : AbstractMcpTool<GetSymbolsOverviewArgs>(GetSymbolsOverviewArgs.serializer()) {

    override val name = "get_symbols_overview"
    override val description = """
        Returns an overview of all top-level symbols (classes, interfaces, enums) in a file,
        optionally including their members (methods, fields) — without reading the full file content.
        This is token-efficient: use it to understand a file's structure before deciding which parts to read.
        Parameters:
          - path: relative (to project root) or absolute path to the file (required)
          - depth: 0 = top-level classes only, 1 = include methods and fields (default: 1)
        Works with both source files and decompiled .class files inside JARs.
        Tip: use this before get_file_text_by_path to avoid reading files you don't need.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Relative or absolute path to the file (e.g. BOOT-INF/lib/foo.jar!/com/example/Bar.class)")
            })
            put("depth", buildJsonObject {
                put("type", "integer")
                put("description", "0 = top-level classes only, 1 = include methods and fields (default: 1)")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
    }

    override fun handle(project: Project, args: GetSymbolsOverviewArgs): Response {
        if (args.path.isBlank()) {
            return Response(error = "path must not be empty")
        }

        val basePath = project.basePath ?: ""
        val absolutePath = if (args.path.startsWith("/")) args.path
        else "$basePath/${args.path}"

        val scope = GlobalSearchScope.allScope(project)

        // Derive the class name from path to look it up via PSI index
        // e.g. ".../com/example/Foo.class" → "com.example.Foo"
        val fqnFromPath = absolutePath
            .substringAfterLast("!/")   // strip jar prefix if any
            .removePrefix("/")
            .removeSuffix(".class")
            .removeSuffix(".java")
            .removeSuffix(".kt")
            .replace('/', '.')

        val results = buildJsonArray {
            runReadAction {
                val classes = mutableListOf<PsiClass>()

                // Try exact FQN first
                val exact = JavaPsiFacade.getInstance(project).findClass(fqnFromPath, scope)
                if (exact != null) {
                    classes.add(exact)
                } else {
                    // Fall back to short name (last segment)
                    val shortName = fqnFromPath.substringAfterLast('.')
                    val found = com.intellij.psi.search.PsiShortNamesCache
                        .getInstance(project)
                        .getClassesByName(shortName, scope)
                    // filter by path match
                    found.filterTo(classes) { psiClass ->
                        val vfPath = psiClass.containingFile?.virtualFile?.path ?: ""
                        vfPath.contains(shortName)
                    }
                    if (classes.isEmpty()) found.toCollection(classes)
                }

                classes.forEach { psiClass ->
                    val vf = psiClass.containingFile?.virtualFile
                    val filePath = vf?.path?.let {
                        if (it.startsWith(basePath)) it.removePrefix("$basePath/") else it
                    } ?: ""

                    add(buildJsonObject {
                        put("qualifiedName", psiClass.qualifiedName ?: psiClass.name ?: "")
                        put("kind", when {
                            psiClass.isInterface -> "interface"
                            psiClass.isEnum -> "enum"
                            psiClass.isAnnotationType -> "annotation"
                            else -> "class"
                        })
                        put("path", filePath)

                        if (args.depth >= 1) {
                            put("methods", buildJsonArray {
                                psiClass.methods.forEach { method: PsiMethod ->
                                    add(buildJsonObject {
                                        put("name", method.name)
                                        put("signature", buildSignature(method))
                                        put("returnType", method.returnType?.presentableText ?: "void")
                                    })
                                }
                            })
                            put("fields", buildJsonArray {
                                psiClass.fields.forEach { field: PsiField ->
                                    add(buildJsonObject {
                                        put("name", field.name)
                                        put("type", field.type.presentableText)
                                    })
                                }
                            })
                            put("innerClasses", buildJsonArray {
                                psiClass.innerClasses.forEach { inner: PsiClass ->
                                    add(buildJsonObject {
                                        put("name", inner.name ?: "")
                                        put("kind", when {
                                            inner.isInterface -> "interface"
                                            inner.isEnum -> "enum"
                                            else -> "class"
                                        })
                                    })
                                }
                            })
                        }
                    })
                }
            }
        }

        if (results.size == 0) {
            return Response(error = "No symbols found for path: ${args.path}")
        }
        return Response(status = results.toString())
    }

    private fun buildSignature(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(", ") { p ->
            "${p.type.presentableText} ${p.name}"
        }
        return "${method.name}($params)"
    }
}
