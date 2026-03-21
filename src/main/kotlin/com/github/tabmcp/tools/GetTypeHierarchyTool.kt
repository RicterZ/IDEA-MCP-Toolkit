package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ClassInheritorsSearch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class GetTypeHierarchyArgs(
    val className: String = "",         // simple or fully qualified class name
    val hierarchyType: String = "both", // "super", "sub", or "both"
    val depth: Int = 3,
    val maxResults: Int = 50
)

class GetTypeHierarchyTool : AbstractMcpTool<GetTypeHierarchyArgs>(GetTypeHierarchyArgs.serializer()) {

    override val name = "get_type_hierarchy"
    override val description = """
        Returns the type hierarchy of a class or interface — its supertypes (parents) and/or subtypes (children/implementations).
        Useful for understanding inheritance chains and finding all implementations of an interface.
        Parameters:
          - className: simple name (e.g. "Response") or fully qualified name (e.g. "com.example.Response") (required)
          - hierarchyType: "super" for parent classes/interfaces, "sub" for subclasses/implementations, "both" for both directions (default: "both")
          - depth: how many levels deep to traverse supertypes (default: 3)
          - maxResults: maximum number of subtypes to return (default: 50)
        Each result includes the qualified name, kind, and file path.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("className", buildJsonObject {
                put("type", "string")
                put("description", "Simple or fully qualified class name (e.g. \"Response\" or \"com.example.Response\")")
            })
            put("hierarchyType", buildJsonObject {
                put("type", "string")
                put("description", "\"super\" for parents, \"sub\" for children/implementations, \"both\" for both directions (default: \"both\")")
            })
            put("depth", buildJsonObject {
                put("type", "integer")
                put("description", "Levels deep to traverse supertypes (default: 3)")
            })
            put("maxResults", buildJsonObject {
                put("type", "integer")
                put("description", "Maximum number of subtypes to return (default: 50)")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("className")) })
    }

    override fun handle(project: Project, args: GetTypeHierarchyArgs): Response {
        if (args.className.isBlank()) {
            return Response(error = "className must not be empty")
        }

        val basePath = project.basePath ?: ""
        val scope = GlobalSearchScope.allScope(project)

        fun psiClassToEntries(builder: kotlinx.serialization.json.JsonObjectBuilder, psiClass: PsiClass) {
            val vf = psiClass.containingFile?.virtualFile
            val filePath = vf?.path?.let {
                if (it.startsWith(basePath)) it.removePrefix("$basePath/") else it
            } ?: ""
            builder.put("qualifiedName", psiClass.qualifiedName ?: psiClass.name ?: "")
            builder.put("kind", when {
                psiClass.isInterface -> "interface"
                psiClass.isEnum -> "enum"
                psiClass.isAnnotationType -> "annotation"
                else -> "class"
            })
            builder.put("path", filePath)
        }

        fun psiClassToJson(psiClass: PsiClass): JsonObject {
            val vf = psiClass.containingFile?.virtualFile
            val filePath = vf?.path?.let {
                if (it.startsWith(basePath)) it.removePrefix("$basePath/") else it
            } ?: ""
            return buildJsonObject {
                put("qualifiedName", psiClass.qualifiedName ?: psiClass.name ?: "")
                put("kind", when {
                    psiClass.isInterface -> "interface"
                    psiClass.isEnum -> "enum"
                    psiClass.isAnnotationType -> "annotation"
                    else -> "class"
                })
                put("path", filePath)
            }
        }

        val result = buildJsonObject {
            runReadAction {
                // Find the root class
                val psiClass = if (args.className.contains('.')) {
                    JavaPsiFacade.getInstance(project).findClass(args.className, scope)
                } else {
                    PsiShortNamesCache.getInstance(project)
                        .getClassesByName(args.className, scope)
                        .firstOrNull()
                } ?: run {
                    return@runReadAction
                }

                put("root", psiClassToJson(psiClass))

                // Supertypes (walk up the chain)
                if (args.hierarchyType == "super" || args.hierarchyType == "both") {
                    put("supertypes", buildJsonArray {
                        fun collectSupers(cls: PsiClass, currentDepth: Int) {
                            if (currentDepth > args.depth) return
                            cls.superClass?.let { superCls ->
                                if (superCls.qualifiedName != "java.lang.Object") {
                                    add(buildJsonObject {
                                        put("depth", currentDepth)
                                        put("relation", "extends")
                                        psiClassToEntries(this, superCls)
                                    })
                                    collectSupers(superCls, currentDepth + 1)
                                }
                            }
                            cls.interfaces.forEach { iface ->
                                add(buildJsonObject {
                                    put("depth", currentDepth)
                                    put("relation", "implements")
                                    psiClassToEntries(this, iface)
                                })
                                collectSupers(iface, currentDepth + 1)
                            }
                        }
                        collectSupers(psiClass, 1)
                    })
                }

                // Subtypes (subclasses + implementations)
                if (args.hierarchyType == "sub" || args.hierarchyType == "both") {
                    put("subtypes", buildJsonArray {
                        var count = 0
                        ClassInheritorsSearch.search(psiClass, scope, true)
                            .forEach { subClass ->
                                if (count >= args.maxResults) return@forEach
                                add(buildJsonObject {
                                    put("relation", if (psiClass.isInterface) "implements" else "extends")
                                    psiClassToEntries(this, subClass)
                                })
                                count++
                            }
                    })
                }
            }
        }

        if (!result.containsKey("root")) {
            return Response(error = "Class not found: ${args.className}")
        }
        return Response(status = result.toString())
    }
}
