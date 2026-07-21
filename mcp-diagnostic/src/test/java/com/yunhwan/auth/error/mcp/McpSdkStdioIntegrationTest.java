package com.yunhwan.auth.error.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpSdkStdioIntegrationTest {

    @Test
    void SDK_client가_stdio_server와_협상하고_tool_목록을_조회한다() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        ServerParameters parameters = ServerParameters.builder(java)
                .args("-cp", System.getProperty("java.class.path"), McpDiagnosticServer.class.getName())
                .env(Map.of(
                        "MCP_DB_URL", "jdbc:postgresql://localhost:5432/auth_pipeline",
                        "MCP_DB_USERNAME", "readonly",
                        "MCP_DB_PASSWORD", "not-used-by-tools-list"
                ))
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());

        try (McpSyncClient client = McpClient.sync(transport)
                .initializationTimeout(Duration.ofSeconds(10))
                .requestTimeout(Duration.ofSeconds(10))
                .build()) {
            client.initialize();

            assertThat(client.isInitialized()).isTrue();
            assertThat(client.getCurrentInitializationResult().protocolVersion()).isNotBlank();
            assertThat(client.listTools().tools()).extracting(tool -> tool.name()).containsExactlyInAnyOrder(
                    "get_auth_error_summary",
                    "get_auth_error_trend",
                    "get_top_auth_error_types",
                    "get_auth_error_clusters",
                    "get_dlq_summary",
                    "get_retry_summary",
                    "get_incident_snapshot",
                    "trace_auth_error"
            );
            assertThat(client.listTools().tools()).allSatisfy(tool -> {
                assertThat(tool.annotations().readOnlyHint()).isTrue();
                assertThat(tool.annotations().destructiveHint()).isFalse();
                assertThat(tool.annotations().openWorldHint()).isFalse();
            });
        }
    }
}
