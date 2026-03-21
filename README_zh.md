# IDEA MCP Toolkit

[English](./README.md) | 中文

一个功能完整的 [MCP（模型上下文协议）](https://modelcontextprotocol.io/) JetBrains IDEA 插件，向 AI 助手（如 Claude）暴露代码智能工具，让 AI 能够直接通过 IDE 深度理解你的代码库。

## 功能特性

| 工具 | 描述 |
|---|---|
| `initialize_toolkit` | **首先调用此工具。** 返回工具使用指南和最佳实践 |
| `get_open_in_editor_file_text` | 读取当前激活的编辑器标签页内容 |
| `get_file_text_by_path` | 通过路径读取文件（支持相对路径、绝对路径、JAR 内部路径） |
| `list_open_tabs` | 列出所有当前打开的编辑器标签页 |
| `get_tab_file_text` | 读取已打开的标签页内容（不切换焦点） |
| `find_symbol` | 通过简单名或全限定名查找类/接口/枚举 |
| `find_in_files` | 在项目源文件中全文搜索（类似 Cmd+Shift+F） |
| `find_everywhere` | 搜索类、方法、字段、文件及 Spring MVC URL（类似双击 Shift） |
| `get_symbols_overview` | 获取文件结构（类、方法、字段），无需读取完整内容 |
| `find_referencing_symbols` | 查找符号的所有引用（类似 Find Usages） |
| `get_type_hierarchy` | 获取类或接口的继承链 |

## 工作原理

插件利用 IDEA 内置的 Netty 服务器，运行一个兼容 MCP 协议的 HTTP+SSE 服务：

- `GET  /api/mcp/sse` — 建立 SSE 长连接（MCP Transport 端点）
- `POST /api/mcp/message?sessionId=xxx` — 接收 JSON-RPC 2.0 请求，响应通过对应 SSE channel 推回

实现了 [MCP 2024-11-05 HTTP+SSE Transport](https://spec.modelcontextprotocol.io/specification/basic/transports/) 规范。

所有工具基于 IDEA 的完整 PSI（程序结构接口）索引，**可搜索项目源码及所有依赖 JAR**，包括反编译的 `.class` 文件。

## 安装

1. 构建插件：
   ```bash
   ./gradlew buildPlugin
   ```
   产物：`build/distributions/IDEA-MCP-Toolkit-1.0.5.zip`

2. 在 IDEA 中：**Settings → Plugins → ⚙️ → Install Plugin from Disk...** → 选择 ZIP 文件

3. 重启 IDEA

## 配置

### Claude Desktop

添加到 `~/Library/Application Support/Claude/claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "idea": {
      "url": "http://localhost:63342/api/mcp/sse"
    }
  }
}
```

> **注意：** 端口可能不同，可在 **Settings → Build, Execution, Deployment → Debugger → Built-in server** 中查看实际端口。

### Claude Code

添加到 `~/.claude.json` 的 `mcpServers` 下：

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

## 工具使用说明

### 推荐工作流

**定位一个类 → 了解结构 → 读取详情**
1. `find_symbol(className="OrderService")` → 获取路径
2. `get_symbols_overview(path="src/.../OrderService.java")` → 扫描方法/字段
3. `get_file_text_by_path(path="src/.../OrderService.java")` → 仅在需要完整实现时调用

**查找方法的所有调用方**
1. `find_symbol(className="PaymentService")` → 确认全限定名
2. `find_referencing_symbols(symbolName="charge", className="com.example.PaymentService")`

**查找接口的所有实现**
1. `get_type_hierarchy(className="com.example.Repository", hierarchyType="sub")`

**追踪 Spring 接口**
1. `find_everywhere(query="/api/orders", searchSpringUrls=true)` → 找到 Controller 及路径
2. `get_symbols_overview(path="src/.../OrderController.java")`
3. `find_referencing_symbols(symbolName="createOrder", className="com.example.OrderController")`

### 参数说明

- **`maxResults`**：所有搜索类工具均支持，设为 `0` 表示不限制返回数量
- **路径格式**：均相对于项目根目录；JAR 内部路径格式：`BOOT-INF/lib/foo.jar!/com/example/Bar.class`
- **`find_everywhere` Spring URL 搜索**：查询时带或不带前导 `/` 均可正确匹配

## 系统要求

- JetBrains IDEA 2024.2+
- Java 21+
- 依赖 IDEA 内置插件：`com.intellij.java` 和 `com.intellij.spring.mvc`

## 开发

```bash
# 构建插件 ZIP
./gradlew buildPlugin

# 在沙箱 IDEA 实例中运行调试
./gradlew runIde
```

## License

MIT
