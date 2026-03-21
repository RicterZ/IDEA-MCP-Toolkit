# IDEA MCP Toolkit

A comprehensive [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) plugin for JetBrains IDEA that exposes code intelligence tools, enabling AI assistants like Claude to deeply understand your codebase directly through the IDE.

## Features

| Tool | Description |
|---|---|
| `initialize_toolkit` | **Call this first.** Returns workflow instructions and best practices for using the toolkit |
| `get_open_in_editor_file_text` | Read the currently active editor tab |
| `get_file_text_by_path` | Read any file by path (relative, absolute, or JAR-internal) |
| `list_open_tabs` | List all currently open editor tabs |
| `get_tab_file_text` | Read an open tab without switching focus |
| `find_symbol` | Find classes/interfaces/enums by simple or fully qualified name |
| `find_in_files` | Full-text search across project files (like Cmd+Shift+F) |
| `find_everywhere` | Search classes, methods, fields, files, and Spring MVC URLs (like double-Shift) |
| `get_symbols_overview` | Get a file's structure (classes, methods, fields) without reading full content |
| `find_referencing_symbols` | Find all usages of a symbol (like Find Usages) |
| `get_type_hierarchy` | Get the inheritance chain of a class or interface |

## How It Works

The plugin runs an MCP-compatible HTTP+SSE server inside IDEA using the built-in Netty server:

- `GET  /api/mcp/sse` — Opens an SSE stream (MCP transport endpoint)
- `POST /api/mcp/message?sessionId=xxx` — Receives JSON-RPC 2.0 requests, responses are sent back over the SSE stream

This implements the [MCP 2024-11-05 HTTP+SSE Transport](https://spec.modelcontextprotocol.io/specification/basic/transports/) spec.

## Installation

1. Build the plugin:
   ```bash
   ./gradlew buildPlugin
   ```
   Output: `build/distributions/IDEA-MCP-Toolkit-1.0.5.zip`

2. In IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk...** → select the ZIP

3. Restart IDEA

## Claude Desktop Configuration

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "idea": {
      "url": "http://localhost:63342/api/mcp/sse"
    }
  }
}
```

> **Note:** The port may vary. Check IDEA's built-in server port at **Settings → Build, Execution, Deployment → Debugger → Built-in server**.

## Claude Code Configuration

Add to `~/.claude.json` (under the `mcpServers` key):

```json
{
  "mcpServers": {
    "idea": {
      "type": "sse",
      "url": "http://localhost:63342/api/mcp/sse"
    }
  }
}
```

## Requirements

- JetBrains IDEA 2024.2+
- Java 21+
- The plugin depends on bundled plugins: `com.intellij.java` and `com.intellij.spring.mvc`

## Development

```bash
# Build plugin ZIP
./gradlew buildPlugin

# Run in sandbox IDEA instance
./gradlew runIde
```

## License

MIT
