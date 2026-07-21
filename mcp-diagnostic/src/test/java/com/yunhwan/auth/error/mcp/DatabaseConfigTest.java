package com.yunhwan.auth.error.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseConfigTest {

    @Test
    void mcp_db_url이_우선된다() {
        DatabaseConfig config = DatabaseConfig.fromEnvironment(Map.of(
                "MCP_DB_URL", "jdbc:postgresql://db:5432/auth_pipeline",
                "MCP_DB_USERNAME", "readonly",
                "MCP_DB_PASSWORD", "secret",
                "DB_NAME", "ignored",
                "DB_USERNAME", "manager",
                "DB_PASSWORD", "manager0"
        ));

        assertThat(config.url()).isEqualTo("jdbc:postgresql://db:5432/auth_pipeline");
        assertThat(config.username()).isEqualTo("readonly");
        assertThat(config.password()).isEqualTo("secret");
        assertThat(config.connectTimeoutSeconds()).isEqualTo(3);
        assertThat(config.queryTimeoutSeconds()).isEqualTo(5);
        assertThat(config.maxConcurrentQueries()).isEqualTo(2);
    }

    @Test
    void 기존_db_env로_jdbc_url을_구성한다() {
        DatabaseConfig config = DatabaseConfig.fromEnvironment(Map.of(
                "DB_HOST", "localhost",
                "DB_PORT", "5432",
                "DB_NAME", "auth_pipeline",
                "DB_USERNAME", "manager",
                "DB_PASSWORD", "manager0"
        ));

        assertThat(config.url()).isEqualTo("jdbc:postgresql://localhost:5432/auth_pipeline");
        assertThat(config.username()).isEqualTo("manager");
    }

    @Test
    void 필수_설정이_없으면_실패한다() {
        assertThatThrownBy(() -> DatabaseConfig.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP_DB_URL");
    }

    @Test
    void 운영_보호_설정을_환경변수에서_읽는다() {
        DatabaseConfig config = DatabaseConfig.fromEnvironment(Map.of(
                "MCP_DB_URL", "jdbc:postgresql://db:5432/auth_pipeline",
                "MCP_DB_USERNAME", "readonly",
                "MCP_DB_PASSWORD", "secret",
                "MCP_DB_CONNECT_TIMEOUT_SECONDS", "7",
                "MCP_DB_QUERY_TIMEOUT_SECONDS", "11",
                "MCP_DB_MAX_CONCURRENT_QUERIES", "4"
        ));

        assertThat(config.connectTimeoutSeconds()).isEqualTo(7);
        assertThat(config.queryTimeoutSeconds()).isEqualTo(11);
        assertThat(config.maxConcurrentQueries()).isEqualTo(4);
    }

    @Test
    void 잘못된_운영_보호_설정은_기동_전에_거부한다() {
        assertThatThrownBy(() -> DatabaseConfig.fromEnvironment(Map.of(
                "MCP_DB_URL", "jdbc:postgresql://db:5432/auth_pipeline",
                "MCP_DB_USERNAME", "readonly",
                "MCP_DB_PASSWORD", "secret",
                "MCP_DB_QUERY_TIMEOUT_SECONDS", "0"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP_DB_QUERY_TIMEOUT_SECONDS");
    }
}
