package com.yunhwan.auth.error.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class McpDiagnosticServer {

    public static void main(String[] args) {
        SpringApplication.run(McpDiagnosticServer.class, args);
    }

    @Bean
    DatabaseConfig databaseConfig() {
        return DatabaseConfig.fromEnvironment(System.getenv());
    }

    @Bean
    DiagnosticRepository diagnosticRepository(DatabaseConfig config) {
        return new DiagnosticRepository(config);
    }
}
