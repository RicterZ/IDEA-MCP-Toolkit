package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class GetTabFileTextArgs(val path: String = "")

class GetTabFileTextTool : AbstractMcpTool<GetTabFileTextArgs>(GetTabFileTextArgs.serializer()) {

    override val name = "get_tab_file_text"
    override val description = """
        Returns the full text content of an already-open tab, identified by its absolute path.
        PREFER this over get_file_text_by_path when the file is already open — it does NOT switch the active tab or disturb the user.
        If the path is not currently open as a tab, returns an error (use get_file_text_by_path instead).
        Workflow: call list_open_tabs to get paths, then call this tool for each file you need to read.
        Supports any text file the IDE has loaded, including decompiled .class files.
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Absolute path of the file to read (must already be open as a tab)")
            })
        })
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
    }

    override fun handle(project: Project, args: GetTabFileTextArgs): Response {
        if (args.path.isBlank()) {
            return Response(error = "path must not be empty")
        }

        val virtualFile = FileEditorManager.getInstance(project).openFiles
            .find { it.path == args.path }
            ?: return Response(error = "No open tab found for path: ${args.path}")

        val text = runReadAction {
            FileDocumentManager.getInstance().getDocument(virtualFile)?.text
        } ?: return Response(error = "Could not read document for: ${args.path}")

        return Response(status = text)
    }
}
