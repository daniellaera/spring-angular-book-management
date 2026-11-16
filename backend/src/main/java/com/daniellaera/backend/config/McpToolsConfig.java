package com.daniellaera.backend.config;

import com.daniellaera.backend.ai.BookTools;
import com.daniellaera.backend.ai.BookToolsService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publishes the same read-only BookTools the chat uses; the MCP server starter exposes every ToolCallbackProvider bean. */
@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider bookMcpTools(BookToolsService service) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new BookTools(service, Integer.MAX_VALUE, "mcp"))
                .build();
    }
}
