package com.hify.api;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only source contract. No application bootstrap, code generation or import audit. */
final class ModuleStructureContract {
    private record Module(String name, String marker, Set<String> roots, String port) {}
    private static final List<Module> MODULES=List.of(
        new Module("provider","ProviderModule",Set.of("com.hify.provider","com.hify.runtime"),"com.hify.provider.api.ProviderService"),
        new Module("tool","ToolModule",Set.of("com.hify.tool","com.hify.runtime"),"com.hify.tool.api.ToolCatalog"),
        new Module("mcp","McpModule",Set.of("com.hify.mcp"),"com.hify.mcp.api.McpCapabilityPort"),
        new Module("agent","AgentModule",Set.of("com.hify.agent","com.hify.domain","com.hify.infra"),"com.hify.agent.api.AgentQueryService"),
        new Module("chat","ChatModule",Set.of("com.hify.chat","com.hify.application","com.hify.domain","com.hify.infra","com.hify.intent","com.hify.memory","com.hify.runtime"),null),
        new Module("knowledge","KnowledgeModule",Set.of("com.hify.knowledge"),"com.hify.knowledge.api.KnowledgeRetrievalPort"),
        new Module("workflow","WorkflowModule",Set.of("com.hify.workflow"),"com.hify.workflow.api.WorkflowCapabilityPort"));
    // Existing technical debt. Subsets are allowed; new packages/owners are not.
    private static final Map<String,Set<String>> SPLIT_OWNERS=Map.of(
        "com.hify.domain",Set.of("agent","chat"),
        "com.hify.infra",Set.of("agent","chat"),
        "com.hify.runtime",Set.of("provider","tool","chat"));
    private record Type(Path file, Tree.Kind kind, Set<Modifier> modifiers) {}

    /** Input contains com.hify dependency declarations found in each module POM.
     * This conservative source check is not Maven's effective dependency graph,
     * does not expand properties, and does not constitute a dependency-cycle audit. */
    static void verifyDependencyDirection(Map<String,Set<String>> dependencies) {
        for(String module:List.of("hify-common","hify-provider","hify-tool"))
            require(dependencies.containsKey(module),"missing dependency inventory: "+module);
        require(dependencies.get("hify-common").isEmpty(),"common must not depend on business modules");
        for(String module:List.of("hify-provider","hify-tool"))
            require(!dependencies.get(module).contains("hify-chat"),"forbidden reverse dependency: "+module+" -> hify-chat");
    }

    static void verify(Path backend) throws IOException {
        Map<String,Set<String>> owners=new LinkedHashMap<>();
        Map<String,String> typeOwners=new LinkedHashMap<>();
        for (Module module:MODULES) {
            Path source=backend.resolve("hify-"+module.name()+"/src/main/java").toAbsolutePath().normalize();
            require(Files.isDirectory(source),"missing source root: "+module.name());
            Map<String,Type> types=parse(source,module,owners,typeOwners);
            String marker="com.hify."+module.name()+"."+module.marker();
            requireType(source,types,marker,Tree.Kind.CLASS,true);
            if(module.port()!=null) requireType(source,types,module.port(),Tree.Kind.INTERFACE,false);
            // Historical public entry point: do not manufacture an empty chat/api layer.
            if(module.name().equals("chat"))
                requireType(source,types,"com.hify.application.RunApplicationService",Tree.Kind.CLASS,false);
        }
        for(var entry:owners.entrySet()) {
            if(entry.getValue().size()<2) continue;
            require(SPLIT_OWNERS.getOrDefault(entry.getKey(),Set.of()).containsAll(entry.getValue()),
                "new split package or owner: "+entry.getKey()+" "+entry.getValue());
        }
    }

    private static Map<String,Type> parse(Path source,Module module,Map<String,Set<String>> owners,
                                          Map<String,String> typeOwners) throws IOException {
        List<Path> files;
        try(var walk=Files.walk(source)) {
            files=walk.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".java")).sorted().toList();
        }
        require(!files.isEmpty(),"empty source root: "+module.name());
        var compiler=ToolProvider.getSystemJavaCompiler();
        require(compiler!=null,"JDK compiler required for source structure contract");
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        Map<String,Type> types=new LinkedHashMap<>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,StandardCharsets.UTF_8)) {
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,
                List.of("-proc:none","--release","17"),null,manager.getJavaFileObjectsFromPaths(files));
            // Parse only. No annotation processors, dependency resolution or class output.
            for(CompilationUnitTree unit:task.parse()) {
                String pkg=unit.getPackageName()==null?"":unit.getPackageName().toString();
                require(module.roots().stream().anyMatch(root->pkg.equals(root)||pkg.startsWith(root+".")),
                    "unapproved package root: "+module.name()+" "+pkg);
                Path file=Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                require(file.getParent().equals(source.resolve(pkg.replace('.','/'))),
                    "package/path mismatch: "+file+" declares "+pkg);
                owners.computeIfAbsent(pkg,ignored->new LinkedHashSet<>()).add(module.name());
                for(Tree declaration:unit.getTypeDecls()) {
                    if(!(declaration instanceof ClassTree type)) continue;
                    String name=pkg+"."+type.getSimpleName();
                    require(typeOwners.putIfAbsent(name,module.name())==null,"duplicate top-level type: "+name);
                    types.put(name,new Type(file,type.getKind(),Set.copyOf(type.getModifiers().getFlags())));
                }
            }
            List<String> errors=new ArrayList<>();
            diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR)
                .forEach(d->errors.add(d.getCode()));
            require(errors.isEmpty(),"invalid Java source syntax: "+module.name()+" "+errors);
        }
        return types;
    }

    private static void requireType(Path source,Map<String,Type> types,String name,Tree.Kind kind,boolean isFinal) {
        Type type=types.get(name);
        require(type!=null,"missing required declaration: "+name);
        require(type.file().equals(source.resolve(name.replace('.','/')+".java")),"required type path mismatch: "+name);
        require(type.kind()==kind,"required type kind mismatch: "+name);
        require(type.modifiers().contains(Modifier.PUBLIC),"required type must be public: "+name);
        require(!isFinal||type.modifiers().contains(Modifier.FINAL),"module marker must be final: "+name);
    }

    private static void require(boolean condition,String message) {
        if(!condition) throw new IllegalStateException(message);
    }
}
