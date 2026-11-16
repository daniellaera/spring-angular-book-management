package com.daniellaera.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.FileReader;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the REAL packaged main application.yml (target/classes, built from src/main/resources) the way Spring Boot does at startup (the test classpath has its own
 * application.yml that shadows it, so the other tests never read it) with ONLY the variables deploy/compose.prod.yml
 * gives the backend container, and resolves every property. Catches what broke the 0058028 deploy before it reaches
 * prod: YAML syntax errors, and placeholders without a default whose variable compose does not provide.
 */
class ProdConfigLoadingTest {

    // the Maven-filtered main application.yml (@project.version@ resolved), i.e. exactly what is packaged in the jar
    private static final String REAL_APPLICATION_YML = "target/classes/application.yml";
    private static final String COMPOSE = "../deploy/compose.prod.yml";

    @SuppressWarnings("unchecked")
    private static Set<String> composeBackendEnvNames() throws Exception {
        try (Reader r = new FileReader(COMPOSE)) {
            Map<String, Object> compose = new Yaml().load(r);
            Map<String, Object> services = (Map<String, Object>) compose.get("services");
            Object env = ((Map<String, Object>) services.get("book-backend")).get("environment");
            assertThat(env).as("book-backend.environment in compose.prod.yml").isInstanceOf(Map.class);
            return ((Map<String, Object>) env).keySet();
        }
    }

    private static ConfigurableEnvironment loadRealConfig(Set<String> providedEnv) {
        ConfigurableEnvironment env = new StandardEnvironment();
        // drop the machine's real environment: only what compose provides may exist
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("spring.config.location", "file:" + REAL_APPLICATION_YML);
        providedEnv.forEach(name -> source.put(name, "dummy-" + name.toLowerCase()));
        source.put("QDRANT_URL", "http://qdrant:6333");
        source.put("DATABASE_URL", "jdbc:postgresql://db:5432/bookdb");
        env.getPropertySources().addFirst(new MapPropertySource("prod-like-env", source));
        ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), new DefaultBootstrapContext());
        return env;
    }

    @Test
    void realApplicationYml_loadsAndResolvesWithOnlyTheVariablesComposeProvides() throws Exception {
        ConfigurableEnvironment env = loadRealConfig(composeBackendEnvNames());

        int checked = 0;
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps.getName().contains("application.yml") && ps instanceof EnumerablePropertySource<?> eps) {
                for (String key : eps.getPropertyNames()) {
                    assertThat(env.getProperty(key)).as("property %s must resolve", key).isNotNull();
                    checked++;
                }
            }
        }
        assertThat(checked).as("the real application.yml must have been loaded").isGreaterThan(20);
    }

    @Test
    void realApplicationYml_hasTheAiAndMcpSettings() throws Exception {
        ConfigurableEnvironment env = loadRealConfig(composeBackendEnvNames());
        assertThat(env.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STATELESS");
        assertThat(env.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint")).isEqualTo("/mcp");
        assertThat(env.getProperty("spring.ai.mcp.server.instructions")).contains("book library");
        assertThat(env.getProperty("ai.max-tool-calls")).isEqualTo("3");
        assertThat(env.getProperty("spring.ai.anthropic.chat.options.model")).isNotBlank();
    }

    @Test
    void mcpTokenIsOptionalInConfig_theEndpointJustStaysClosed() throws Exception {
        Set<String> withoutMcp = new java.util.LinkedHashSet<>(composeBackendEnvNames());
        withoutMcp.remove("MCP_API_TOKEN");
        withoutMcp.remove("INDEX_API_TOKEN");
        ConfigurableEnvironment env = loadRealConfig(withoutMcp);
        assertThat(env.getProperty("mcp.api-token")).isEmpty();
        assertThat(env.getProperty("index.api-token")).isEmpty();
    }

    @Test
    void aRequiredVariableMissingFromCompose_isDetected() throws Exception {
        Set<String> withoutJwt = new java.util.LinkedHashSet<>(composeBackendEnvNames());
        withoutJwt.remove("JWT_SECRET");
        ConfigurableEnvironment env = loadRealConfig(withoutJwt);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> env.getProperty("jwt.secret"));
    }
}
