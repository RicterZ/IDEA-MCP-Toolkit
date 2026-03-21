package com.github.tabmcp

import com.intellij.openapi.project.Project
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Response(
    val status: String = "",
    val error: String = ""
)

@Serializable
class NoArgs

interface McpTool<Args : Any> {
    val name: String
    val description: String
    val inputSchema: JsonObject
    fun handle(project: Project, args: Args): Response
}

abstract class AbstractMcpTool<Args : Any>(val serializer: KSerializer<Args>) : McpTool<Args> {
    // Default: no parameters. Override in tools that accept arguments.
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }
}
