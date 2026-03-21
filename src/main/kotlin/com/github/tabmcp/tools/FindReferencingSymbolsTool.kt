package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class FindReferencingSymbolsArgs(
    val symbolName: String = "",        // e.g. "Response.ok" or just "ok"
    val className: String = "",         // optional: containing class FQN to narrow down
    val maxResults: Int = 30
)

class FindReferencingSymbolsTool : AbstractMcpTool<FindReferencingSymbolsArgs>(FindReferencingSymbolsArgs.serializer()) {

    override val name = "find_referencing_symbols"
    override val description = """
        Finds all places that reference (call/use) a given symbol — like IDEA's "Find Usages".
        Returns the referencing file, line number, and a code snippet of the reference context.
        Parameters:
          - symbolName: method or field name to find usages of (required), e.g. "ok", "getUser"
          - className: fully qualified class name to narrow down which symbol, e.g. "com.example.Response" (optional but recommended to avoid ambiguity)
          - maxResults: maximum number of references to return (default: 30); set to 0 for no limit
        Tip: use find_symbol first to get the exact class name, then pass it here.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("symbolName", buildJsonObject {
                put("type", "string")
                put("description", "Method or field name to find usages of (e.g. \"ok\", \"getUser\")")
            })
            put("className", buildJsonObject {
                put("type", "string")
                put("description", "Fully qualified class name to narrow down the symbol (e.g. \"com.h3c.h3cloud.cvk.agent.common.model.Response\")")
            })
            put("maxResults", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of references to return (default: 30); set to 0 for no limit")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("symbolName")) })
    }

    override fun handle(project: Project, args: FindReferencingSymbolsArgs): Response {
        if (args.symbolName.isBlank()) {
            return Response(error = "symbolName must not be empty")
        }

        val basePath = project.basePath ?: ""
        val scope = GlobalSearchScope.allScope(project)
        val projectFileIndex = ProjectFileIndex.getInstance(project)

        val results = buildJsonArray {
            runReadAction {
                val limit = if (args.maxResults <= 0) Int.MAX_VALUE else args.maxResults
                // Collect candidate PSI elements to search references for
                val targets = mutableListOf<com.intellij.psi.PsiElement>()

                if (args.className.isNotBlank()) {
                    // Narrow by class: find the specific method/field in that class
                    val psiClass = JavaPsiFacade.getInstance(project)
                        .findClass(args.className, scope)
                    if (psiClass != null) {
                        psiClass.findMethodsByName(args.symbolName, true).toCollection(targets)
                        psiClass.findFieldByName(args.symbolName, true)?.let { targets.add(it) }
                    }
                } else {
                    // No class specified: search by short name across all classes
                    PsiShortNamesCache.getInstance(project)
                        .getMethodsByName(args.symbolName, scope)
                        .toCollection(targets)
                    PsiShortNamesCache.getInstance(project)
                        .getFieldsByName(args.symbolName, scope)
                        .toCollection(targets)
                }

                if (targets.isEmpty()) {
                    return@runReadAction
                }

                var count = 0
                targets.forEach { target ->
                    if (count >= limit) return@forEach
                    val refs = ReferencesSearch.search(target, scope).findAll()
                    refs.forEach ref@{ ref ->
                        if (count >= limit) return@ref
                        val element = ref.element
                        val vf = element.containingFile?.virtualFile ?: return@ref
                        val filePath = vf.path.let {
                            if (it.startsWith(basePath)) it.removePrefix("$basePath/") else it
                        }
                        val document = com.intellij.openapi.fileEditor.FileDocumentManager
                            .getInstance().getDocument(vf) ?: return@ref
                        val offset = element.textOffset
                        val lineNumber = document.getLineNumber(offset) + 1
                        val lineStart = document.getLineStartOffset(lineNumber - 1)
                        val lineEnd = document.getLineEndOffset(lineNumber - 1)
                        val lineContent = document.getText(
                            com.intellij.openapi.util.TextRange(lineStart, lineEnd)
                        ).trim()

                        // Get containing method/class name for context
                        val containingMethod = com.intellij.psi.util.PsiTreeUtil
                            .getParentOfType(element, PsiMethod::class.java)
                        val containingClass = com.intellij.psi.util.PsiTreeUtil
                            .getParentOfType(element, com.intellij.psi.PsiClass::class.java)
                        val context = when {
                            containingMethod != null ->
                                "${containingClass?.qualifiedName ?: ""}.${containingMethod.name}"
                            containingClass != null ->
                                containingClass.qualifiedName ?: ""
                            else -> ""
                        }

                        add(buildJsonObject {
                            put("path", filePath)
                            put("line", lineNumber)
                            put("content", lineContent)
                            put("context", context)
                            put("inProject", projectFileIndex.isInContent(vf))
                        })
                        count++
                    }
                }
            }
        }

        if (results.size == 0) {
            return Response(error = "No references found for: ${args.symbolName}")
        }
        return Response(status = results.toString())
    }
}
