package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class GetFileTextByPathArgs(val path: String = "")

class GetFileTextByPathTool : AbstractMcpTool<GetFileTextByPathArgs>(GetFileTextByPathArgs.serializer()) {

    override val name = "get_file_text_by_path"
    override val description = """
        Opens a file in the IDE and returns its full text content.
        Use this ONLY when the file is NOT already open as a tab — it will open and activate the file, visibly switching the editor tab.
        If the file might already be open, call list_open_tabs first and use get_tab_file_text instead to avoid disrupting the user.
        Accepts absolute paths or paths relative to the project root (e.g. src/main/kotlin/Foo.kt).
        Also supports JAR-internal paths (e.g. BOOT-INF/lib/foo.jar!/com/example/Bar.class).
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Path to the file to open. Accepts absolute paths or paths relative to the project root. Supports JAR-internal paths (e.g. BOOT-INF/lib/foo.jar!/com/example/Bar.class).")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
    }

    override fun handle(project: Project, args: GetFileTextByPathArgs): Response {
        if (args.path.isBlank()) {
            return Response(error = "path must not be empty")
        }

        // Resolve to absolute: if path doesn't start with "/" it's relative to project root
        val absolutePath = if (args.path.startsWith("/")) {
            args.path
        } else {
            val basePath = project.basePath
                ?: return Response(error = "Cannot resolve relative path: project base path is unknown")
            "$basePath/${args.path}"
        }

        // Support both plain filesystem paths and jar-internal paths (path!/entry)
        val virtualFile: VirtualFile? = if (absolutePath.contains("!/")) {
            VirtualFileManager.getInstance().refreshAndFindFileByUrl("jar://$absolutePath")
        } else {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
        } ?: return Response(error = "File not found: ${args.path}")

        // Open the file in the editor on the EDT, then read its content
        var text: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(virtualFile!!, /* focusEditor = */ true)
            text = runReadAction {
                FileEditorManager.getInstance(project).selectedTextEditor?.document?.text
            }
        }

        return if (text != null) {
            Response(status = text!!)
        } else {
            Response(error = "Could not read file content (file may not be a text file): ${args.path}")
        }
    }
}
