package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.NoArgs
import com.github.tabmcp.Response
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ListOpenTabsTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {

    override val name = "list_open_tabs"
    override val description = """
        Returns a JSON array of all files currently open as tabs in the IDE. Each entry has "path" (absolute) and "name" (filename only).
        Use this FIRST when you need to read multiple files or don't know the exact path — then use get_tab_file_text to read their contents.
        This is a cheap, non-destructive operation: it does not open, close, or switch any tabs.
        Example output: [{"path":"/Users/me/project/src/Foo.kt","name":"Foo.kt"}, ...]
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val files = FileEditorManager.getInstance(project).openFiles
        val result = buildJsonArray {
            files.forEach { file ->
                add(buildJsonObject {
                    put("path", file.path)
                    put("name", file.name)
                })
            }
        }
        return Response(status = result.toString())
    }
}
