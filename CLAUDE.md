# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a JetBrains IntelliJ IDEA plugin that implements an **MCP (Model Context Protocol) server** embedded inside the IDE. It exposes 11 code-intelligence tools over an HTTP+SSE JSON-RPC 2.0 transport running on IDEA's built-in Netty server, allowing AI assistants like Claude to query the IDE's PSI index directly.

## Build & Run Commands

```bash
# Build the plugin ZIP (output: build/distributions/IDEA-MCP-Toolkit-*.zip)
./gradlew buildPlugin

# Run a sandbox IDEA instance with the plugin loaded (for manual testing)
./gradlew runIde
```

**Install**: In IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk → select the ZIP, then restart.

There are no tests and no linter configured in this project.

## Architecture

### Transport Layer (`MCPService.kt`)
`MCPService` extends IDEA's `RestService` and is registered as an `<httpRequestHandler>` in `plugin.xml`. It handles all HTTP routes:
- `GET /api/mcp/sse` — opens an SSE stream, assigns a session ID, stores the Netty `Channel` in a `ConcurrentHashMap<String, Channel>`
- `POST /api/mcp/message?sessionId=xxx` — receives JSON-RPC 2.0 requests and dispatches them to tools; responses are pushed back over the SSE stream
- Legacy routes (`GET /api/mcp/list_tools`, `GET /api/mcp/<tool_name>`) for backward compatibility

The plugin implements **MCP protocol version `2024-11-05`** and handles the `initialize`, `tools/list`, `tools/call`, and `notifications/initialized` JSON-RPC methods.

### Tool Pattern (`McpTool.kt` + `tools/`)
Every tool extends `AbstractMcpTool<Args>` and provides:
- `name: String` — the MCP tool name
- `description: String` — shown to the AI
- `inputSchema: JsonObject` — JSON Schema for parameter validation
- `handle(project: Project, args: Args): Response` — implementation using IntelliJ PSI APIs

Args are `@Serializable` Kotlin data classes deserialized via `kotlinx-serialization-json`. The `Response` type has `status` (success payload as string) and `error` fields.

`MCPService` holds a hardcoded list of all tool instances and dispatches `tools/call` by name.

### Key IDE APIs Used
- `FileEditorManager` — open files, active editor
- `PsiShortNamesCache` / `JavaPsiFacade` — class/method/field lookups
- `ClassInheritorsSearch` — subtype hierarchy
- `ProjectFileIndex` + `GlobalSearchScope` — file iteration scoped to project+dependencies
- `AnnotatedRequestMappingsProcessor` (Spring MVC plugin) — Spring URL mappings
- `VirtualFileManager` / `LocalFileSystem` — file resolution including JAR-internal paths (e.g. `foo.jar!/com/example/Bar.class`)

### Build System
- **IntelliJ Platform Gradle Plugin v2** (`org.jetbrains.intellij.platform` 2.5.0)
- Kotlin 2.1.0, JVM target Java 21, Gradle 8.11
- `gradle.properties` hardcodes `org.gradle.java.home` to a local IDEA JBR — update this if building on a different machine
- Target IDEA builds: `242` (2024.2) through `253.*` (2025.3)
