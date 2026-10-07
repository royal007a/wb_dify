package com.hify.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleStructureContractTest {
    @TempDir Path backend;
    private static final Map<String,String> MARKERS=Map.of(
        "provider","ProviderModule","tool","ToolModule","mcp","McpModule",
        "agent","AgentModule","chat","ChatModule","knowledge","KnowledgeModule","workflow","WorkflowModule");
    private static final Map<String,String> PORTS=Map.of(
        "provider","ProviderService","tool","ToolCatalog","mcp","McpCapabilityPort",
        "agent","AgentQueryService","knowledge","KnowledgeRetrievalPort","workflow","WorkflowCapabilityPort");

    @BeforeEach void fixture() throws Exception {
        for(var entry:MARKERS.entrySet())
            write(entry.getKey(),"com.hify."+entry.getKey(),entry.getValue(),"public final class "+entry.getValue()+" {}");
        for(var entry:PORTS.entrySet())
            write(entry.getKey(),"com.hify."+entry.getKey()+".api",entry.getValue(),"public interface "+entry.getValue()+" {}");
        write("chat","com.hify.application","RunApplicationService","public class RunApplicationService {}");
    }

    @Test void minimalSourceContractDoesNotRequireEmptyLegacyDirectories() {
        assertThatCode(()->ModuleStructureContract.verify(backend)).doesNotThrowAnyException();
    }
    @Test void missingMarkerCannotBeReplacedByAnEmptyDirectory() throws Exception {
        Files.delete(file("provider","com.hify.provider","ProviderModule"));
        rejects("missing required declaration: com.hify.provider.ProviderModule");
    }
    @Test void wrongDeclarationCannotHideBehindExpectedFilename() throws Exception {
        Files.writeString(file("provider","com.hify.provider","ProviderModule"),
            "package com.hify.provider; public final class NotProviderModule {}");
        rejects("missing required declaration: com.hify.provider.ProviderModule");
    }
    @Test void wrongPackageCannotHideBehindExpectedPath() throws Exception {
        Files.writeString(file("provider","com.hify.provider","ProviderModule"),
            "package com.hify.provider.other; public final class ProviderModule {}");
        rejects("package/path mismatch:");
    }
    @Test void missingPortIsRejected() throws Exception {
        Files.delete(file("knowledge","com.hify.knowledge.api","KnowledgeRetrievalPort"));
        rejects("missing required declaration: com.hify.knowledge.api.KnowledgeRetrievalPort");
    }
    @Test void concreteClassDoesNotReplaceRequiredInterface() throws Exception {
        write("knowledge","com.hify.knowledge.api","KnowledgeRetrievalPort","public class KnowledgeRetrievalPort {}");
        rejects("required type kind mismatch: com.hify.knowledge.api.KnowledgeRetrievalPort");
    }
    @Test void newlyIntroducedRootFails() throws Exception {
        write("provider","com.hify.unapproved","Extra","public class Extra {}");
        rejects("unapproved package root: provider com.hify.unapproved");
    }
    @Test void existingSplitPackageOwnersAreAllowed() throws Exception {
        write("provider","com.hify.runtime","ProviderExtra","public class ProviderExtra {}");
        write("tool","com.hify.runtime","ToolExtra","public class ToolExtra {}");
        assertThatCode(()->ModuleStructureContract.verify(backend)).doesNotThrowAnyException();
    }
    @Test void newSplitSubpackageFailsEvenInsideAllowedRoots() throws Exception {
        write("provider","com.hify.runtime.added","ProviderExtra","public class ProviderExtra {}");
        write("tool","com.hify.runtime.added","ToolExtra","public class ToolExtra {}");
        rejects("new split package or owner: com.hify.runtime.added");
    }
    @Test void duplicateClassInGrandfatheredSplitPackageFails() throws Exception {
        write("provider","com.hify.runtime","Shared","public class Shared {}");
        write("tool","com.hify.runtime","Shared","public class Shared {}");
        rejects("duplicate top-level type: com.hify.runtime.Shared");
    }
    @Test void nonPublicMarkerFails() throws Exception {
        write("provider","com.hify.provider","ProviderModule","final class ProviderModule {}");
        rejects("required type must be public: com.hify.provider.ProviderModule");
    }
    @Test void nonFinalMarkerFails() throws Exception {
        write("provider","com.hify.provider","ProviderModule","public class ProviderModule {}");
        rejects("module marker must be final: com.hify.provider.ProviderModule");
    }
    @Test void requiredPortMustStayInItsExactFile() throws Exception {
        Path original=file("knowledge","com.hify.knowledge.api","KnowledgeRetrievalPort");
        Files.move(original,original.resolveSibling("WrongFile.java"));
        rejects("required type path mismatch: com.hify.knowledge.api.KnowledgeRetrievalPort");
    }
    @Test void brokenJavaSourceIsNotAcceptedAsAnInventory() throws Exception {
        write("provider","com.hify.provider","Broken","public class Broken { void broken( }");
        rejects("invalid Java source syntax: provider");
    }
    @Test void commentedDeclarationCannotSpoofPort() throws Exception {
        Files.writeString(file("knowledge","com.hify.knowledge.api","KnowledgeRetrievalPort"),
            "package com.hify.knowledge.api; /* public interface KnowledgeRetrievalPort {} */");
        rejects("missing required declaration: com.hify.knowledge.api.KnowledgeRetrievalPort");
    }

    @Test void allowedDependencyDirectionPasses() {
        assertThatCode(()->ModuleStructureContract.verifyDependencyDirection(Map.of(
            "hify-common",Set.of(),"hify-provider",Set.of("hify-common"),"hify-tool",Set.of("hify-common"))))
            .doesNotThrowAnyException();
    }
    @Test void commonCannotAcquireBusinessDependency() {
        assertThatThrownBy(()->ModuleStructureContract.verifyDependencyDirection(Map.of(
            "hify-common",Set.of("hify-agent"),"hify-provider",Set.of(),"hify-tool",Set.of())))
            .isInstanceOf(IllegalStateException.class).hasMessage("common must not depend on business modules");
    }
    @Test void providerCannotDependOnChat() {
        assertThatThrownBy(()->ModuleStructureContract.verifyDependencyDirection(Map.of(
            "hify-common",Set.of(),"hify-provider",Set.of("hify-chat"),"hify-tool",Set.of())))
            .isInstanceOf(IllegalStateException.class).hasMessage("forbidden reverse dependency: hify-provider -> hify-chat");
    }
    @Test void toolCannotDependOnChat() {
        assertThatThrownBy(()->ModuleStructureContract.verifyDependencyDirection(Map.of(
            "hify-common",Set.of(),"hify-provider",Set.of(),"hify-tool",Set.of("hify-chat"))))
            .isInstanceOf(IllegalStateException.class).hasMessage("forbidden reverse dependency: hify-tool -> hify-chat");
    }
    @Test void absentDependencyInventoryIsNotAnEmptyValidGraph() {
        assertThatThrownBy(()->ModuleStructureContract.verifyDependencyDirection(Map.of()))
            .isInstanceOf(IllegalStateException.class).hasMessage("missing dependency inventory: hify-common");
    }

    private void rejects(String reason) {
        assertThatThrownBy(()->ModuleStructureContract.verify(backend))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(reason);
    }
    private Path file(String module,String pkg,String name) {
        return backend.resolve("hify-"+module+"/src/main/java/"+pkg.replace('.','/')+"/"+name+".java");
    }
    private void write(String module,String pkg,String name,String declaration) throws Exception {
        Path target=file(module,pkg,name);Files.createDirectories(target.getParent());
        Files.writeString(target,"package "+pkg+";\n"+declaration+"\n");
    }
}
