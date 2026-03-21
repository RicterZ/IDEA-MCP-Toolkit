package com.github.tabmcp.tools

import com.github.tabmcp.AbstractMcpTool
import com.github.tabmcp.NoArgs
import com.github.tabmcp.Response
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

class GetCurrentFileTextTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name = "get_open_in_editor_file_text"
    override val description = """
        Returns the full text content of the file the user is currently looking at (the active editor tab).
        Use this when the user says things like "this file", "current file", "the file I have open", or points at something without specifying a path.
        Do NOT use this to read a specific file by name — use get_tab_file_text or get_file_text_by_path instead.
        Also works for decompiled .class files.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val text = runReadAction<String?> {
            FileEditorManager.getInstance(project).selectedTextEditor?.document?.text
        }
        return Response(status = text ?: "")
    }
}
