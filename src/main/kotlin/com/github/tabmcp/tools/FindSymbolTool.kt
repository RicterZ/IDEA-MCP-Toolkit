package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class FindSymbolArgs(val name: String = "")

class FindSymbolTool : AbstractMcpTool<FindSymbolArgs>(FindSymbolArgs.serializer()) {

    override val name = "find_symbol"
    override val description = """
        Searches for a Java/Kotlin class by name across the entire project including all dependency JARs.
        Accepts either a simple class name (e.g. "Response") or a fully qualified name (e.g. "com.example.Response").
        Returns a list of matches with their fully qualified name, kind (class/interface/enum/annotation),
        and the file path (relative to project root) which can be passed directly to get_file_text_by_path.
        Use this to locate any class before opening it with get_file_text_by_path.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "Simple class name (e.g. \"Response\") or fully qualified name (e.g. \"com.example.Response\")")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("name")) })
    }

    override fun handle(project: Project, args: FindSymbolArgs): Response {
        if (args.name.isBlank()) {
            return Response(error = "name must not be empty")
        }

        val scope = GlobalSearchScope.allScope(project)
        val projectFileIndex = ProjectFileIndex.getInstance(project)
        val basePath = project.basePath ?: ""

        val classes: Array<PsiClass> = runReadAction {
            if (args.name.contains('.')) {
                val found = JavaPsiFacade.getInstance(project).findClass(args.name, scope)
                if (found != null) arrayOf(found) else emptyArray()
            } else {
                PsiShortNamesCache.getInstance(project).getClassesByName(args.name, scope)
            }
        }

        if (classes.isEmpty()) {
            return Response(error = "No class found for: ${args.name}")
        }

        val results = buildJsonArray {
            runReadAction {
                classes.forEach { psiClass ->
                    val qualifiedName = psiClass.qualifiedName ?: return@forEach
                    val virtualFile = psiClass.containingFile?.virtualFile ?: return@forEach
                    val filePath = virtualFile.path

                    // Make path relative to project root if possible
                    val relativePath = if (filePath.startsWith(basePath)) {
                        filePath.removePrefix("$basePath/")
                    } else {
                        filePath
                    }

                    val kind = when {
                        psiClass.isInterface -> "interface"
                        psiClass.isEnum -> "enum"
                        psiClass.isAnnotationType -> "annotation"
                        else -> "class"
                    }

                    val isInProject = projectFileIndex.isInContent(virtualFile)

                    add(buildJsonObject {
                        put("qualifiedName", qualifiedName)
                        put("kind", kind)
                        put("path", relativePath)
                        put("inProject", isInProject)
                    })
                }
            }
        }

        return Response(status = results.toString())
    }
}
