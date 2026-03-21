package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class InitializeToolkitArgs(
    val placeholder: String = ""
)

class InitializeToolkitTool : AbstractMcpTool<InitializeToolkitArgs>(InitializeToolkitArgs.serializer()) {

    override val name = "initialize_toolkit"
    override val description = """
        IMPORTANT: Call this tool first before starting any code analysis task.
        Returns workflow instructions and best practices for using the IDEA MCP Toolkit effectively.
        Always call this at the beginning of a new task so you know how to use the available tools correctly.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override fun handle(project: Project, args: InitializeToolkitArgs): Response {
        val instructions = """
# IDEA MCP Toolkit

Backed by JetBrains IDEA's full PSI index — searches project sources AND dependency JARs.
Never ask the user to paste code; use the tools to find and read it yourself.

Project: ${project.name}  |  Base path: ${project.basePath ?: "unknown"}

---

## Tools

### Discovery
- **find_everywhere** — broad search (classes, methods, fields, files, Spring URLs)
  `find_everywhere(query="UserService")`
  `find_everywhere(query="/api/login", searchSpringUrls=true)`
- **find_symbol** — locate a class by name, returns qualified name + path
  `find_symbol(className="Response")`
  `find_symbol(className="com.example.UserService")`
- **find_in_files** — full-text search (string literals, annotations, comments)
  `find_in_files(pattern="@PreAuthorize", filePattern="*.java")`

### Reading Files
- **get_symbols_overview** — class structure without full content; call this BEFORE get_file_text_by_path
  `get_symbols_overview(path="src/main/java/com/example/UserService.java")`
  `get_symbols_overview(path="BOOT-INF/lib/common.jar!/com/example/model/User.class")`
- **get_file_text_by_path** — full file content; supports relative, absolute, and JAR-internal paths
  `get_file_text_by_path(path="src/main/java/com/example/UserService.java")`
  `get_file_text_by_path(path="BOOT-INF/lib/common.jar!/com/example/model/User.class")`
- **list_open_tabs** — list currently open editor tabs
- **get_tab_file_text** — read an open tab without switching focus
  `get_tab_file_text(path="src/main/java/com/example/Foo.java")`
- **get_open_in_editor_file_text** — read the file the user is currently viewing

### Code Relationships
- **find_referencing_symbols** — find all usages of a method/field (like Find Usages)
  `find_referencing_symbols(symbolName="authenticate", className="com.example.AuthService")`
- **get_type_hierarchy** — inheritance chain; hierarchyType: "super" / "sub" / "both"; maxResults=0 for no limit
  `get_type_hierarchy(className="com.example.BaseController", hierarchyType="sub")`
  `get_type_hierarchy(className="com.example.Repository", hierarchyType="sub", maxResults=0)`

---

## Workflows

**Locate a class → understand it → read details**
1. `find_symbol(className="OrderService")` → get path
2. `get_symbols_overview(path="src/.../OrderService.java")` → scan methods/fields
3. `get_file_text_by_path(path="src/.../OrderService.java")` → only if full body needed

**Find all usages of a method**
1. `find_symbol(className="PaymentService")` → confirm qualified name
2. `find_referencing_symbols(symbolName="charge", className="com.example.PaymentService")`

**Find all implementations of an interface**
1. `get_type_hierarchy(className="com.example.Repository", hierarchyType="sub")`

**Trace a Spring endpoint**
1. `find_everywhere(query="/api/orders", searchSpringUrls=true)` → get handler class + path
2. `get_symbols_overview(path="src/.../OrderController.java")`
3. `find_referencing_symbols(symbolName="createOrder", className="com.example.OrderController")`

---

## Rules
- Always `get_symbols_overview` before `get_file_text_by_path` to save tokens
- Always pass `className` to `find_referencing_symbols` to avoid ambiguous matches
- Paths are relative to project root; JAR syntax: `BOOT-INF/lib/foo.jar!/com/example/Bar.class`
        """.trimIndent()

        return Response(status = instructions)
    }
}
