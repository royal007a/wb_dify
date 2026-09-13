package com.hify.api;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MavenStructureTest {
    private static final Set<String> MODULES = Set.of(
            "hify-common", "hify-provider", "hify-tool", "hify-mcp", "hify-agent",
            "hify-chat", "hify-knowledge", "hify-workflow", "hify-demo", "hify-app");

    @Test
    void parentModulesMatchDirectoriesAndDependencies() throws Exception {
        Path backend = Path.of("..").toAbsolutePath().normalize();
        Document parent = parse(backend.resolve("pom.xml"));
        Set<String> declared = new LinkedHashSet<>();
        NodeList moduleNodes = parent.getElementsByTagName("module");
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            declared.add(moduleNodes.item(i).getTextContent().trim());
        }

        Set<String> directories = new LinkedHashSet<>();
        try (var children = Files.list(backend)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("hify-"))
                    .filter(path -> Files.exists(path.resolve("pom.xml")))
                    .map(path -> path.getFileName().toString())
                    .forEach(directories::add);
        }
        assertThat(declared).containsExactlyInAnyOrderElementsOf(MODULES);
        assertThat(directories).containsExactlyInAnyOrderElementsOf(MODULES);

        assertInternalDependencies(backend.resolve("hify-chat/pom.xml"),
                "hify-agent", "hify-provider", "hify-tool");
        assertInternalDependencies(backend.resolve("hify-agent/pom.xml"), "hify-mcp");
        assertInternalDependencies(backend.resolve("hify-app/pom.xml"), "hify-demo");
    }

    @Test
    void businessModulePackageSkeletonsAreConsistent() {
        Path backend = Path.of("..").toAbsolutePath().normalize();
        for (String name : List.of("provider", "tool", "mcp", "agent", "chat", "knowledge", "workflow")) {
            Path root = backend.resolve("hify-" + name + "/src/main/java/com/hify/" + name);
            for (String packagePath : List.of("controller", "service", "service/impl",
                    "mapper", "entity", "dto", "config")) {
                assertThat(root.resolve(packagePath))
                        .as("%s must contain %s", name, packagePath)
                        .isDirectory();
            }
        }
    }

    private void assertInternalDependencies(Path pom, String... expected) throws Exception {
        Document document = parse(pom);
        List<String> artifacts = new ArrayList<>();
        NodeList dependencies = document.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            if ("com.hify".equals(text(dependency, "groupId"))) {
                artifacts.add(text(dependency, "artifactId"));
            }
        }
        assertThat(artifacts).contains(expected);
    }

    private Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getParentNode() == parent) return node.getTextContent().trim();
        }
        return "";
    }
}
