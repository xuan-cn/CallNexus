package org.dromara.test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
@Tag("prod")
class BackendEnvironmentConfigurationTest {

    @Test
    void productionUsesDeploymentVariables() throws IOException {
        Map<String, Object> values = Map.ofEntries(
            Map.entry("CNX_JDBC_URL", "jdbc:mysql://test-db:3306/test"),
            Map.entry("MYSQL_USER", "test-user"), Map.entry("MYSQL_PASSWORD", "test-password"),
            Map.entry("REDIS_PASSWORD", "test-redis"), Map.entry("CNX_SNAIL_TOKEN", "test-job-token"),
            Map.entry("CNX_MONITOR_USER", "test-monitor"), Map.entry("CNX_MONITOR_PASSWORD", "test-monitor-password"),
            Map.entry("CNX_API_REQUEST_PRIVATE_KEY", "test-request-key"),
            Map.entry("CNX_API_RESPONSE_PUBLIC_KEY", "test-response-key"),
            Map.entry("CALLNEXUS_DATA_ENCRYPT_KEY", "0123456789abcdef"),
            Map.entry("CNX_JWT_SECRET", "test-jwt"), Map.entry("CNX_REALTIME_TOKEN_SECRET", "test-realtime"),
            Map.entry("CALLNEXUS_FREESWITCH_DIRECTORY_SECRET", "test-directory"),
            Map.entry("CALLNEXUS_AI_QDRANT_URL", "http://test-vector:6333"),
            Map.entry("QDRANT_API_KEY", "test-vector-key"));
        ConfigurableEnvironment env = environment("prod", values);
        assertEquals(values.get("CNX_JDBC_URL"), env.getProperty("spring.datasource.dynamic.datasource.master.url"));
        assertEquals("test-user", env.getProperty("spring.datasource.dynamic.datasource.master.username"));
        assertEquals("test-password", env.getProperty("spring.datasource.dynamic.datasource.master.password"));
        assertEquals("test-redis", env.getProperty("spring.data.redis.password"));
        assertEquals("test-job-token", env.getProperty("snail-job.token"));
        assertEquals("test-request-key", env.getProperty("api-decrypt.privateKey"));
        assertEquals("test-response-key", env.getProperty("api-decrypt.publicKey"));
        assertEquals("0123456789abcdef", env.getProperty("mybatis-encryptor.password"));
        assertEquals("test-jwt", env.getProperty("sa-token.jwt-secret-key"));
        assertEquals("test-directory", env.getProperty("callnexus.freeswitch.directory.secret"));
        assertEquals("test-realtime", env.getProperty("ai.realtime-token-secret"));
        assertEquals("test-vector-key", env.getProperty("ai.qdrant-api-key"));
        assertEquals("http://test-vector:6333", env.getProperty("ai.qdrant-url"));
        assertEquals("false", env.getProperty("spring.datasource.dynamic.p6spy"));
    }

    @Test
    void productionDoesNotFallBackToDevelopmentSecrets() throws IOException {
        ConfigurableEnvironment env = environment("prod", Map.of());
        for (String key : List.of("spring.datasource.dynamic.datasource.master.url",
            "spring.datasource.dynamic.datasource.master.password", "spring.data.redis.password",
            "snail-job.token", "sa-token.jwt-secret-key", "api-decrypt.privateKey", "api-decrypt.publicKey",
            "mybatis-encryptor.password", "callnexus.freeswitch.directory.secret",
            "ai.realtime-token-secret", "ai.qdrant-api-key")) {
            assertThrows(IllegalArgumentException.class, () -> env.getProperty(key), key);
        }
    }

    @Test
    void developmentKeepsDefaultsAndSupportsOverrides() throws IOException {
        ConfigurableEnvironment defaults = environment("dev", Map.of());
        assertTrue(defaults.getProperty("spring.datasource.dynamic.datasource.master.url").contains("192.168.244.128"));
        assertEquals("192.168.244.128", defaults.getProperty("spring.data.redis.host"));
        assertFalse(defaults.getProperty("api-decrypt.privateKey").isBlank());
        ConfigurableEnvironment overrides = environment("dev", Map.of(
            "MYSQL_PASSWORD", "local-password", "REDIS_HOST", "localhost",
            "CNX_API_REQUEST_PRIVATE_KEY", "local-request", "CNX_JWT_SECRET", "local-jwt"));
        assertEquals("local-password", overrides.getProperty("spring.datasource.dynamic.datasource.master.password"));
        assertEquals("localhost", overrides.getProperty("spring.data.redis.host"));
        assertEquals("local-request", overrides.getProperty("api-decrypt.privateKey"));
        assertEquals("local-jwt", overrides.getProperty("sa-token.jwt-secret-key"));
    }

    private ConfigurableEnvironment environment(String profile, Map<String, Object> values) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        // Ignore the machine's real credentials so tests remain isolated and deterministic.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test-values", values));
        appendYaml(env, "application-" + profile + ".yml");
        appendYaml(env, "application.yml");
        return env;
    }

    private void appendYaml(ConfigurableEnvironment env, String name) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(name, new ClassPathResource(name));
        for (int index = sources.size() - 1; index >= 0; index--) {
            env.getPropertySources().addLast(sources.get(index));
        }
    }
}
