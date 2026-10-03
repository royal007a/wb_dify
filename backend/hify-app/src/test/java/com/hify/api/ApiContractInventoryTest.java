package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Inventory gate, deliberately not presented as behavioral endpoint coverage. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-api-inventory;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class ApiContractInventoryTest {
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void everyRegisteredApiRouteHasExactlyOneDocumentedContract() throws Exception {
        Path root = repositoryRoot();
        JsonNode contract = json.readTree(root.resolve("docs/spec/http-api.json").toFile());
        assertThat(contract.path("schemaVersion").asInt()).isEqualTo(1);
        Map<String, String> expected = new TreeMap<>();
        for (JsonNode endpoint : contract.path("endpoints")) {
            String key = endpoint.path("method").asText() + " " + endpoint.path("path").asText();
            for (String required : new String[]{"method", "path", "handler", "source", "feature",
                    "purpose", "request", "response", "checks"}) {
                assertThat(endpoint.path(required).asText()).as(key + " " + required).isNotBlank();
            }
            assertThat(endpoint.path("success").asInt()).as(key).isBetween(200, 299);
            assertThat(Files.isRegularFile(root.resolve(endpoint.path("source").asText())))
                    .as(key + " source exists").isTrue();
            for (JsonNode candidate : endpoint.path("testCandidates")) {
                assertThat(Files.isRegularFile(root.resolve(candidate.asText())))
                        .as(key + " candidate test exists; not proof it exercises route").isTrue();
            }
            assertThat(expected.put(key, endpoint.path("handler").asText()))
                    .as("duplicate contract: " + key).isNull();
        }
        Map<String, String> actual = new TreeMap<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/")) continue;
                assertThat(mapping.getMethodsCondition().getMethods())
                        .as("Unbounded method mapping must have an explicit contract: " + path).isNotEmpty();
                mapping.getMethodsCondition().getMethods().forEach(method -> {
                    String key = method.name() + " " + path;
                    assertThat(actual.put(key, handler.getBeanType().getSimpleName()))
                            .as("Method/path overload needs richer contract identity: " + key).isNull();
                });
            }
        });
        assertThat(actual).isNotEmpty().containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("docs/spec/http-api.json"))) {
            current = current.getParent();
        }
        if (current == null) throw new AssertionError("Missing docs/spec/http-api.json; run from repository");
        return current;
    }
}
