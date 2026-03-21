package com.github.tabmcp

import com.github.tabmcp.tools.FindEverywhereTool
import com.github.tabmcp.tools.FindInFilesTool
import com.github.tabmcp.tools.FindReferencingSymbolsTool
import com.github.tabmcp.tools.FindSymbolTool
import com.github.tabmcp.tools.GetCurrentFileTextTool
import com.github.tabmcp.tools.GetFileTextByPathTool
import com.github.tabmcp.tools.GetSymbolsOverviewTool
import com.github.tabmcp.tools.GetTabFileTextTool
import com.github.tabmcp.tools.GetTypeHierarchyTool
import com.github.tabmcp.tools.InitializeToolkitTool
import com.github.tabmcp.tools.ListOpenTabsTool
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.RestService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MCPService : RestService() {

    private val json = Json { ignoreUnknownKeys = true }

    private val sessions = ConcurrentHashMap<String, Channel>()

    private val tools: List<AbstractMcpTool<*>> = listOf(
        InitializeToolkitTool(),
        GetCurrentFileTextTool(),
        GetFileTextByPathTool(),
        ListOpenTabsTool(),
        GetTabFileTextTool(),
        FindSymbolTool(),
        FindInFilesTool(),
        FindEverywhereTool(),
        GetSymbolsOverviewTool(),
        FindReferencingSymbolsTool(),
        GetTypeHierarchyTool()
    )

    override fun getServiceName(): String = "mcp"

    override fun isMethodSupported(method: HttpMethod): Boolean =
        method == HttpMethod.GET || method == HttpMethod.POST

    @Throws(Exception::class)
    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): String? {
        val path = urlDecoder.path()
            .removePrefix("/api/${getServiceName()}/")
            .trimEnd('/')

        return when (path) {
            "sse" -> {
                handleSse(request, context)
                null
            }
            "message" -> {
                handleMessage(urlDecoder, request, context)
                null
            }
            // Legacy paths for backward compatibility
            "list_tools" -> handleLegacyListTools(request, context)
            else -> handleLegacyToolCall(path, request, context)
        }
    }

    // ── SSE endpoint ─────────────────────────────────────────────────────────

    private fun handleSse(request: FullHttpRequest, context: ChannelHandlerContext) {
        val sessionId = UUID.randomUUID().toString()
        val channel = context.channel()

        sessions[sessionId] = channel
        channel.closeFuture().addListener { sessions.remove(sessionId) }

        // Send SSE response headers (no body yet, keep connection open)
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().apply {
            set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
            set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
            set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
            set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        }
        channel.writeAndFlush(response)

        // Send the endpoint event so the client knows where to POST messages
        val port = BuiltInServerManager.getInstance().waitForStart().port
        val endpointUrl = "http://localhost:$port/api/mcp/message?sessionId=$sessionId"
        val endpointFrame = "event: endpoint\ndata: $endpointUrl\n\n"
        channel.writeAndFlush(
            DefaultHttpContent(Unpooled.copiedBuffer(endpointFrame, Charsets.UTF_8))
        )
        // Do NOT close the channel — the SSE stream must stay open
    }

    // ── Message endpoint ──────────────────────────────────────────────────────

    private fun handleMessage(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ) {
        val sessionId = urlDecoder.parameters()["sessionId"]?.firstOrNull()
        val sseChannel = sessionId?.let { sessions[it] }

        if (sseChannel == null) {
            sendStatus(HttpResponseStatus.NOT_FOUND, false, context.channel())
            return
        }

        val body = request.content().toString(Charsets.UTF_8)
        val rpcRequest = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            sendStatus(HttpResponseStatus.BAD_REQUEST, false, context.channel())
            return
        }

        val method = rpcRequest["method"]?.jsonPrimitive?.content ?: ""
        val id: JsonElement? = rpcRequest["id"]
        val params = rpcRequest["params"]?.jsonObject

        when (method) {
            "initialize" -> {
                val result = buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("serverInfo", buildJsonObject {
                        put("name", "idea-mcp-tab-reader")
                        put("version", "1.0.0")
                    })
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject {})
                    })
                }
                writeSseMessage(sseChannel, id, result)
            }

            "notifications/initialized" -> {
                // Notification — no SSE response needed, just acknowledge the POST
            }

            "tools/list" -> {
                val result = buildJsonObject {
                    put("tools", buildJsonArray {
                        tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("inputSchema", tool.inputSchema)
                            })
                        }
                    })
                }
                writeSseMessage(sseChannel, id, result)
            }

            "tools/call" -> {
                val toolName = params?.get("name")?.jsonPrimitive?.content ?: ""
                val tool = tools.find { it.name == toolName }

                if (tool == null) {
                    val errorResult = buildJsonObject {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", "Tool '$toolName' not found")
                            })
                        })
                        put("isError", JsonPrimitive(true))
                    }
                    writeSseMessage(sseChannel, id, errorResult)
                } else {
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    if (project == null) {
                        val errorResult = buildJsonObject {
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "No open project found")
                                })
                            })
                            put("isError", JsonPrimitive(true))
                        }
                        writeSseMessage(sseChannel, id, errorResult)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val typedTool = tool as AbstractMcpTool<Any>
                        // Use arguments from params if present, otherwise fall back to empty object
                        val argsJson = params?.get("arguments")?.toString() ?: "{}"
                        val args = json.decodeFromString(typedTool.serializer, argsJson)
                        val response = typedTool.handle(project, args)

                        val text = if (response.error.isNotEmpty()) response.error else response.status
                        val result = buildJsonObject {
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                })
                            })
                        }
                        writeSseMessage(sseChannel, id, result)
                    }
                }
            }

            else -> {
                // Unknown method — send a JSON-RPC error back over SSE
                val errorPayload = buildJsonObject {
                    put("jsonrpc", "2.0")
                    id?.let { put("id", it) }
                    put("error", buildJsonObject {
                        put("code", JsonPrimitive(-32601))
                        put("message", "Method not found: $method")
                    })
                }
                val frame = "event: message\ndata: $errorPayload\n\n"
                sseChannel.writeAndFlush(
                    DefaultHttpContent(Unpooled.copiedBuffer(frame, Charsets.UTF_8))
                )
            }
        }

        // Always respond to the POST with 202 Accepted
        sendStatus(HttpResponseStatus.ACCEPTED, false, context.channel())
    }

    // ── SSE helper ────────────────────────────────────────────────────────────

    private fun writeSseMessage(channel: Channel, id: JsonElement?, result: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            put("result", result)
        }
        val frame = "event: message\ndata: $payload\n\n"
        channel.writeAndFlush(
            DefaultHttpContent(Unpooled.copiedBuffer(frame, Charsets.UTF_8))
        )
    }

    // ── Legacy endpoints (backward-compat) ────────────────────────────────────

    private fun handleLegacyListTools(
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): String? {
        sendJson(buildJsonObject {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", JsonPrimitive(tool.name))
                        put("description", JsonPrimitive(tool.description))
                        put("inputSchema", buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", buildJsonObject {})
                        })
                    })
                }
            })
        }.toString(), request, context)
        return null
    }

    private fun handleLegacyToolCall(
        path: String,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): String? {
        val tool = tools.find { it.name == path }
        if (tool == null) {
            sendJson(
                buildJsonObject {
                    put("error", JsonPrimitive("Tool '$path' not found"))
                }.toString(),
                request, context
            )
            return null
        }

        val project = ProjectManager.getInstance().openProjects.firstOrNull()
            ?: run {
                sendJson(
                    buildJsonObject {
                        put("error", JsonPrimitive("No open project found"))
                    }.toString(),
                    request, context
                )
                return null
            }

        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as AbstractMcpTool<Any>
        val args = json.decodeFromString(typedTool.serializer, "{}")
        val result = typedTool.handle(project, args)

        sendJson(
            buildJsonObject {
                put("status", JsonPrimitive(result.status))
                if (result.error.isNotEmpty()) {
                    put("error", JsonPrimitive(result.error))
                }
            }.toString(),
            request, context
        )
        return null
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun sendJson(
        jsonBody: String,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ) {
        val outputStream = BufferExposingByteArrayOutputStream()
        outputStream.writer(Charsets.UTF_8).use { writer ->
            writer.write(jsonBody)
            writer.flush()
        }
        sendData(
            outputStream.toByteArray(),
            "application/json; charset=UTF-8",
            request,
            context.channel(),
            request.headers()
        )
    }

    private fun sendStatus(status: HttpResponseStatus, keepAlive: Boolean, channel: Channel) {
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        if (!keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        }
        val future = channel.writeAndFlush(response)
        if (!keepAlive) {
            future.addListener { channel.close() }
        }
    }
}
