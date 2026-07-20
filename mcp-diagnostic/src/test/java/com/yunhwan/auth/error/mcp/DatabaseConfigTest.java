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
}
