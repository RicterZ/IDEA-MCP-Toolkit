package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class GetMethodSourceArgs(
    val methodName: String = "",
    val className: String = ""   // optional: simple name or FQN
)

class GetMethodSourceTool : AbstractMcpTool<GetMethodSourceArgs>(GetMethodSourceArgs.serializer()) {

    override val name = "get_method_source"

    override val description = """
        Returns the full source code of a method (or all its overloads) by name, as a JSON object.
        Each key is a unique method signature; each value is the complete source text including
        annotations, modifiers, parameter list, and body.
        Parameters:
          - methodName: simple method name (required), e.g. "processOrder"
          - className: simple or fully qualified class name to narrow the search (optional),
                       e.g. "OrderService" or "com.example.OrderService".
                       When omitted, ALL project-wide matches are returned.
        Key format:
          - With className:    "ReturnType methodName(ParamType paramName, ...)"
          - Without className: "com.example.OrderService#ReturnType methodName(ParamType paramName, ...)"
        Methods in bytecode-only JARs (no source attached) have the value
        "[source not available — bytecode only]".
        Tip: use find_symbol first to get the exact FQN, then pass it as className.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("methodName", buildJsonObject {
                put("type", "string")
                put("description", "Simple method name to search for (e.g. \"processOrder\")")
            })
            put("className", buildJsonObject {
                put("type", "string")
                put("description",
                    "Simple or fully qualified class name to narrow the search " +
                    "(e.g. \"OrderService\" or \"com.example.OrderService\"). " +
                    "When omitted, all project-wide methods with this name are returned.")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("methodName")) })
    }

    override fun handle(project: Project, args: GetMethodSourceArgs): Response {
        if (args.methodName.isBlank()) {
            return Response(error = "methodName must not be empty")
        }

        val scope = GlobalSearchScope.allScope(project)
        // When className is not specified we include the FQN in the key to distinguish results
        val prependClass = args.className.isBlank()

        val resultObj: JsonObject? = runReadAction {
            val methods: List<PsiMethod> = if (args.className.isNotBlank()) {
                // Locate the class first, then find methods declared directly on it
                val psiClass = if (args.className.contains('.')) {
                    // Looks like a FQN — use precise lookup
                    JavaPsiFacade.getInstance(project).findClass(args.className, scope)
                } else {
                    // Short name — take the first match (guide users to use FQN to be precise)
                    PsiShortNamesCache.getInstance(project)
                        .getClassesByName(args.className, scope)
                        .firstOrNull()
                } ?: return@runReadAction null   // class not found

                // false = only methods declared on this class, not inherited ones
                psiClass.findMethodsByName(args.methodName, false).toList()
            } else {
                // No class specified — search the entire project
                PsiShortNamesCache.getInstance(project)
                    .getMethodsByName(args.methodName, scope)
                    .toList()
            }

            if (methods.isEmpty()) return@runReadAction null

            buildJsonObject {
                methods.forEach { method ->
                    put(buildKey(method, prependClass), methodSource(method))
                }
            }
        }

        if (resultObj == null) {
            val location = if (args.className.isNotBlank())
                "in class '${args.className}'"
            else
                "in project (searched globally)"
            return Response(error = "No method named '${args.methodName}' found $location")
        }

        return Response(status = resultObj.toString())
    }

    /**
     * Build a human-readable, unique key for the method.
     *
     * prependClass = true  → "com.example.OrderService#void processOrder(String orderId)"
     * prependClass = false → "void processOrder(String orderId)"
     */
    private fun buildKey(method: PsiMethod, prependClass: Boolean): String {
        val params = method.parameterList.parameters.joinToString(", ") { p ->
            "${p.type.presentableText} ${p.name}"
        }
        val returnType = method.returnType?.presentableText ?: "void"
        val signature = "$returnType ${method.name}($params)"
        return if (prependClass) {
            val fqn = method.containingClass?.qualifiedName
                ?: method.containingClass?.name
                ?: "?"
            "$fqn#$signature"
        } else {
            signature
        }
    }

    /**
     * Return the full source text of the method if available,
     * or a placeholder for bytecode-only entries (no source attached).
     */
    private fun methodSource(method: PsiMethod): String =
        if (method.body != null) method.text
        else "[source not available — bytecode only]"
}
