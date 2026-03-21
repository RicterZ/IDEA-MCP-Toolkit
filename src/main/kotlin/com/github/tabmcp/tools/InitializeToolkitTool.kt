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
# IDEA MCP Toolkit — Instructions Manual

You have access to the IDEA MCP Toolkit, a set of code intelligence tools backed by JetBrains IDEA's
full PSI (Program Structure Interface) index. This means you can search across the entire project,
including all dependency JARs, decompiled .class files, and source files.

## IMPORTANT: Always read this before starting work
This is the IDEA MCP Toolkit. It provides tools to read files, search symbols, inspect type hierarchies,
find usages, and more — all powered by the live IDEA index. Use these tools instead of guessing or
asking the user to paste code.

---

## Available Tools & When to Use Each

### 🔍 Discovery (start here)
- **find_everywhere** — Your first stop. Like double-Shift in IDEA. Searches classes, methods, fields,
  files, and Spring MVC URLs simultaneously. Use this when you don't know where something is.
- **find_symbol** — When you know a class name. Returns qualified name + file path for use with other tools.
  Searches both project sources AND dependency JARs.
- **find_in_files** — Full-text search across project files (like Cmd+Shift+F). Use for string literals,
  annotations, comments, or any text pattern that isn't a symbol name.

### 📄 Reading Files
- **get_symbols_overview** — ALWAYS use this before reading a file. Returns the class structure
  (methods, fields, inner classes) without reading full content. Token-efficient.
- **get_file_text_by_path** — Read a file's full content. Supports:
  - Relative paths (relative to project root): `src/main/kotlin/com/example/Foo.kt`
  - Absolute paths: `/Users/me/project/src/...`
  - JAR-internal paths: `BOOT-INF/lib/foo.jar!/com/example/Bar.class`
- **get_tab_file_text** — Read a file that's already open in a tab (non-destructive, no tab switching).
- **list_open_tabs** — See what files are currently open in the editor.
- **get_open_in_editor_file_text** — Read whatever the user is currently looking at.

### 🔗 Code Relationships
- **find_referencing_symbols** — Find all usages of a method/field (like Find Usages). Provide
  `className` to avoid ambiguity. Returns file, line, and surrounding code context.
- **get_type_hierarchy** — Get the inheritance chain of a class. Use `hierarchyType`:
  - `"super"` — parent classes and interfaces
  - `"sub"` — all subclasses and implementations
  - `"both"` — full hierarchy (default)

---

## Recommended Workflows

### Workflow 1: "Where is X defined?"
1. `find_everywhere(query="X")` or `find_symbol(className="X")`
2. Note the file path from the result
3. `get_symbols_overview(path="...")` to understand the class structure
4. `get_file_text_by_path(path="...")` only if you need the full implementation

### Workflow 2: "How is X used?"
1. `find_symbol(className="ClassName")` to get the fully qualified name
2. `find_referencing_symbols(symbolName="methodName", className="com.example.ClassName")`
3. Read specific files using `get_file_text_by_path` as needed

### Workflow 3: "What implements interface X?"
1. `find_symbol(className="X")` to confirm the qualified name
2. `get_type_hierarchy(className="com.example.X", hierarchyType="sub")`
3. Read specific implementations as needed

### Workflow 4: "How does this Spring endpoint work?"
1. `find_everywhere(query="/api/path", searchSpringUrls=true)` to locate the handler
2. `get_symbols_overview(path="...")` on the controller class
3. `find_referencing_symbols` to find callers of service methods

---

## Best Practices

1. **Always call `get_symbols_overview` before `get_file_text_by_path`** — it saves tokens and often
   gives you enough information without reading the full file.

2. **Provide `className` to `find_referencing_symbols`** — without it, results may include unrelated
   symbols with the same method name from other classes.

3. **Use relative paths** — all tools that accept a path support paths relative to the project root.
   Prefer relative over absolute for clarity.

4. **`find_everywhere` vs `find_symbol`** — use `find_everywhere` for broad discovery; use `find_symbol`
   when you know the exact class name and want the qualified name + path quickly.

5. **Dependency JARs are fully indexed** — you can read decompiled `.class` files from JARs using
   `get_file_text_by_path` with the JAR path syntax:
   `BOOT-INF/lib/some-library.jar!/com/example/SomeClass.class`

6. **Don't ask the user to paste code** — use the tools to find and read it yourself.

---

## Project Info
- Project: ${project.name}
- Base path: ${project.basePath ?: "unknown"}
        """.trimIndent()

        return Response(status = instructions)
    }
}
