package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.spring.mvc.mapping.UrlMappingElement
import com.intellij.spring.mvc.model.mappings.processors.AnnotatedRequestMappingsProcessor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class FindEverywhereArgs(
    val query: String = "",
    val searchClasses: Boolean = true,
    val searchMethods: Boolean = true,
    val searchFiles: Boolean = true,
    val searchSpringUrls: Boolean = true,
    val maxResults: Int = 30
)

class FindEverywhereTool : AbstractMcpTool<FindEverywhereArgs>(FindEverywhereArgs.serializer()) {

    override val name = "find_everywhere"
    override val description = """
        Searches across the entire project like IDEA's "Search Everywhere" (double Shift).
        Finds classes, methods, fields, files, and Spring MVC URL mappings by name or path.
        Parameters:
          - query: the name or path fragment to search for (required)
          - searchClasses: include class/interface/enum matches (default: true)
          - searchMethods: include method and field matches (default: true)
          - searchFiles: include file name matches (default: true)
          - searchSpringUrls: include Spring MVC @RequestMapping URL matches (default: true)
          - maxResults: max results per category (default: 30)
        Each result includes its kind, qualified name or URL, and file path for use with get_file_text_by_path.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", "Name or path fragment to search for (e.g. \"Response\", \"getUser\", \"/api/cmd\")")
            })
            put("searchClasses", buildJsonObject {
                put("type", "boolean")
                put("description", "Include class/interface/enum matches (default: true)")
            })
            put("searchMethods", buildJsonObject {
                put("type", "boolean")
                put("description", "Include method and field matches (default: true)")
            })
            put("searchFiles", buildJsonObject {
                put("type", "boolean")
                put("description", "Include file name matches (default: true)")
            })
            put("searchSpringUrls", buildJsonObject {
                put("type", "boolean")
                put("description", "Include Spring MVC @RequestMapping URL matches (default: true)")
            })
            put("maxResults", buildJsonObject {
                put("type", "integer")
                put("description", "Max results per category (default: 30)")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    }

    override fun handle(project: Project, args: FindEverywhereArgs): Response {
        if (args.query.isBlank()) {
            return Response(error = "query must not be empty")
        }

        val query = args.query.trim()
        val basePath = project.basePath ?: ""
        val scope = GlobalSearchScope.allScope(project)
        val projectFileIndex = ProjectFileIndex.getInstance(project)

        fun VirtualFilePath(path: String) = if (path.startsWith(basePath)) {
            path.removePrefix("$basePath/")
        } else path

        val results = buildJsonObject {

            // ── Classes ───────────────────────────────────────────────────────
            if (args.searchClasses) {
                put("classes", buildJsonArray {
                    var count = 0
                    runReadAction {
                        val cache = PsiShortNamesCache.getInstance(project)
                        // collect all class names that contain the query
                        cache.allClassNames
                            .filter { it.contains(query, ignoreCase = true) }
                            .take(args.maxResults * 2)
                            .forEach outer@{ className ->
                                if (count >= args.maxResults) return@outer
                                cache.getClassesByName(className, scope).forEach { psiClass ->
                                    if (count >= args.maxResults) return@forEach
                                    val fqn = psiClass.qualifiedName ?: return@forEach
                                    val vf = psiClass.containingFile?.virtualFile ?: return@forEach
                                    val kind = when {
                                        psiClass.isInterface -> "interface"
                                        psiClass.isEnum -> "enum"
                                        psiClass.isAnnotationType -> "annotation"
                                        else -> "class"
                                    }
                                    add(buildJsonObject {
                                        put("kind", kind)
                                        put("name", fqn)
                                        put("path", VirtualFilePath(vf.path))
                                        put("inProject", projectFileIndex.isInContent(vf))
                                    })
                                    count++
                                }
                            }
                    }
                })
            }

            // ── Methods & Fields ──────────────────────────────────────────────
            if (args.searchMethods) {
                put("symbols", buildJsonArray {
                    var count = 0
                    runReadAction {
                        val cache = PsiShortNamesCache.getInstance(project)
                        // Methods
                        cache.allMethodNames
                            .filter { it.contains(query, ignoreCase = true) }
                            .take(args.maxResults * 2)
                            .forEach outer@{ methodName ->
                                if (count >= args.maxResults) return@outer
                                cache.getMethodsByName(methodName, scope).forEach { method ->
                                    if (count >= args.maxResults) return@forEach
                                    val vf = method.containingFile?.virtualFile ?: return@forEach
                                    val containingClass = method.containingClass?.qualifiedName ?: ""
                                    add(buildJsonObject {
                                        put("kind", "method")
                                        put("name", "$containingClass.${method.name}")
                                        put("path", VirtualFilePath(vf.path))
                                        put("inProject", projectFileIndex.isInContent(vf))
                                    })
                                    count++
                                }
                            }
                        // Fields
                        cache.allFieldNames
                            .filter { it.contains(query, ignoreCase = true) }
                            .take(args.maxResults * 2)
                            .forEach outer@{ fieldName ->
                                if (count >= args.maxResults) return@outer
                                cache.getFieldsByName(fieldName, scope).forEach { field ->
                                    if (count >= args.maxResults) return@forEach
                                    val vf = field.containingFile?.virtualFile ?: return@forEach
                                    val containingClass = field.containingClass?.qualifiedName ?: ""
                                    add(buildJsonObject {
                                        put("kind", "field")
                                        put("name", "$containingClass.${field.name}")
                                        put("path", VirtualFilePath(vf.path))
                                        put("inProject", projectFileIndex.isInContent(vf))
                                    })
                                    count++
                                }
                            }
                    }
                })
            }

            // ── Files ─────────────────────────────────────────────────────────
            if (args.searchFiles) {
                put("files", buildJsonArray {
                    var count = 0
                    runReadAction {
                        projectFileIndex.iterateContent { vf ->
                            if (count >= args.maxResults) return@iterateContent false
                            if (!vf.isDirectory && vf.name.contains(query, ignoreCase = true)) {
                                add(buildJsonObject {
                                    put("kind", "file")
                                    put("name", vf.name)
                                    put("path", VirtualFilePath(vf.path))
                                })
                                count++
                            }
                            true
                        }
                    }
                })
            }

            // ── Spring MVC URLs ───────────────────────────────────────────────
            if (args.searchSpringUrls) {
                put("springUrls", buildJsonArray {
                    var count = 0
                    runReadAction {
                        ModuleManager.getInstance(project).modules.forEach { module ->
                            if (count >= args.maxResults) return@forEach
                            AnnotatedRequestMappingsProcessor.processAnnotationMappings(
                                { mapping: UrlMappingElement ->
                                    if (count >= args.maxResults) return@processAnnotationMappings false
                                    val url = mapping.url ?: return@processAnnotationMappings true
                                    if (!url.contains(query, ignoreCase = true)) return@processAnnotationMappings true

                                    val navTarget = mapping.navigationTarget
                                    val vf = navTarget?.containingFile?.virtualFile
                                    val methods = mapping.method.joinToString(", ") { it.name }
                                    val containingClass = (navTarget as? PsiMethod)
                                        ?.containingClass?.qualifiedName ?: ""
                                    val methodName = (navTarget as? PsiMethod)?.name ?: ""

                                    add(buildJsonObject {
                                        put("kind", "springUrl")
                                        put("url", url)
                                        put("httpMethods", methods)
                                        put("handler", if (methodName.isNotEmpty()) "$containingClass.$methodName" else containingClass)
                                        put("path", vf?.let { VirtualFilePath(it.path) } ?: "")
                                    })
                                    count++
                                    true
                                },
                                module
                            )
                        }
                    }
                })
            }
        }

        return Response(status = results.toString())
    }
}
