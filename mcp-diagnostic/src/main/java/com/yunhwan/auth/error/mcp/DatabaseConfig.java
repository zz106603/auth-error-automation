package com.yunhwan.auth.error.mcp;

import java.util.Map;

record DatabaseConfig(
        String url,
        String username,
        String password,
        int connectTimeoutSeconds,
        int queryTimeoutSeconds,
        int maxConcurrentQueries
) {

    static DatabaseConfig fromEnvironment(Map<String, String> env) {
        String url = firstNonBlank(
                env.get("MCP_DB_URL"),
                jdbcUrlFromParts(env)
        );
        String username = firstNonBlank(env.get("MCP_DB_USERNAME"), env.get("DB_USERNAME"));
        String password = firstNonBlank(env.get("MCP_DB_PASSWORD"), env.get("DB_PASSWORD"));

        if (url == null || username == null || password == null) {
            throw new IllegalArgumentException(
                    "MCP_DB_URL/MCP_DB_USERNAME/MCP_DB_PASSWORD or DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD must be set."
            );
        }
        return new DatabaseConfig(
                url,
                username,
                password,
                positiveInt(env, "MCP_DB_CONNECT_TIMEOUT_SECONDS", 3),
                positiveInt(env, "MCP_DB_QUERY_TIMEOUT_SECONDS", 5),
                positiveInt(env, "MCP_DB_MAX_CONCURRENT_QUERIES", 2)
        );
    }

    private static String jdbcUrlFromParts(Map<String, String> env) {
        String host = firstNonBlank(env.get("DB_HOST"), "localhost");
        String port = firstNonBlank(env.get("DB_PORT"), "5432");
        String name = env.get("DB_NAME");
        if (isBlank(name)) {
            return null;
        }
        return "jdbc:postgresql://" + host + ":" + port + "/" + name;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int positiveInt(Map<String, String> env, String name, int defaultValue) {
        String value = env.get(name);
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer.", exception);
        }
        throw new IllegalArgumentException(name + " must be a positive integer.");
    }
}
