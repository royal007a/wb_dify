package com.hify.workflow.application;

import com.hify.common.BizException;
import com.hify.workflow.api.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.hify.workflow.application.WorkflowFixtures.*;

class WorkflowAggregationTest {
    private final WorkflowGraphValidator validator = new WorkflowGraphValidator();

    static WorkflowNodeSpec merge(String... refs) {
        var config = JSON.createObjectNode();
        var candidates = config.putArray("candidates");
        for (String ref : refs) candidates.add(ref);
        return new WorkflowNodeSpec("merge", "AGGREGATOR", "merge", config);
    }
    static WorkflowDraftRequest graph(WorkflowNodeSpec aggregate) {
        return draft(List.of(node("start", "START"), node("route", "CONDITION", "expression", "{{start.userMessage}} == 'left'"),
                        node("left", "TEMPLATE", "template", "L"), node("right", "TEMPLATE", "template", "R"),
                        aggregate, node("end", "END", "output", "{{merge.result}}")),
                edge("start", "route"), branch("left", "true", false), branch("right", null, true),
                edge("left", "merge"), edge("right", "merge"), edge("merge", "end"));
    }
    private void rejects(WorkflowDraftRequest graph) {
        assertThatThrownBy(() -> validator.validate(graph)).isInstanceOf(BizException.class);
    }

    @Test void acceptsExactlyOneBranchAndKeepsOrdinaryTemplateDominance() {
        validator.validate(graph(merge("left.result", "right.result")));
        var valid = graph(merge("left.result", "right.result"));
        var changed = new ArrayList<>(valid.nodes());
        changed.set(5, node("end", "END", "output", "{{left.result}}"));
        rejects(draft(changed, valid.edges().toArray(WorkflowEdgeSpec[]::new)));
    }

    @Test void rejectsSiblingOnAnotherEndEvenThoughCandidateCountsAreExactlyOne() {
        var g = graph(merge("left.result", "right.result"));
        var ns = new ArrayList<>(g.nodes());
        ns.add(node("outer", "CONDITION", "expression", "true"));
        ns.add(node("sib", "TEMPLATE", "template", "sibling"));
        ns.add(node("sibEnd", "END", "output", "{{sib.result}}"));
        var es = new ArrayList<>(g.edges());
        es.removeIf(e -> e.edgeKey().equals("start-route"));
        es.add(edge("start", "outer"));
        es.add(new WorkflowEdgeSpec("outer-route", "outer", "route", "true", false));
        es.add(new WorkflowEdgeSpec("outer-sib", "outer", "sib", null, true));
        es.add(edge("sib", "sibEnd"));
        validator.validate(draft(ns, es.toArray(WorkflowEdgeSpec[]::new)));
        ns.set(4, merge("left.result", "right.result", "sib.result"));
        assertThatThrownBy(() -> validator.validate(draft(ns, es.toArray(WorkflowEdgeSpec[]::new))))
                .isInstanceOf(BizException.class).hasMessageContaining("严格上游输出");
    }

    @Test void fixedPreAggregationPublicationRetainsRawBytesAndChecksum() throws Exception {
        String raw;
        try (var stream = getClass().getResourceAsStream("/workflow/legacy-7ddff72.json")) {
            assertThat(stream).isNotNull();
            raw = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).stripTrailing();
        }
        String expected = "566f4b6297a4c259beb1c790f73228e60b5a89eb7073ae5f1ac7e4e4bf18e968";
        assertThat(WorkflowPublishedGraph.checksum(raw)).isEqualTo(expected);
        var version = new com.hify.workflow.domain.WorkflowVersion("old-v", "old-w", 1, 1,
                raw, expected, java.time.Instant.EPOCH);
        var loaded = WorkflowPublishedGraph.read(version, JSON);
        validator.validate(loaded);
        assertThat(loaded.nodes().get(2).config().path("historicalExtension").asText()).isEqualTo("retain");
        assertThat(WorkflowPublishedGraph.write(loaded, JSON)).isEqualTo(raw);
        assertThat(version.getDslJson()).isEqualTo(raw);
        assertThat(version.getChecksum()).isEqualTo(expected);
    }

    @Test void rejectsZeroOnOnePathEvenWhenAnotherPathHasOneCandidate() {
        var valid = graph(merge("left.result", "other.result"));
        var ns = new ArrayList<>(valid.nodes());
        ns.add(node("other", "TEMPLATE", "template", "O"));
        var es = new ArrayList<>(valid.edges());
        es.add(branch("other", "false", false));
        es.add(edge("other", "merge"));
        // Default->right has zero, true->left and false->other have one.
        rejects(draft(ns, es.toArray(WorkflowEdgeSpec[]::new)));
    }

    @Test void rejectsTwoOnOnePathAndStartAsAnAdditionalCandidate() {
        rejects(graph(merge("start.userMessage", "left.result")));
        var valid = graph(merge("route.result", "left.result"));
        rejects(valid); // right has one; left has two. Picking first is forbidden.
    }

    @Test void rejectsBadUnknownSelfAndDownstreamReferences() {
        for (String ref : List.of(" left.result", "left.result ", "{{left.result}}", "left.result.extra",
                "missing.result", "left.unknown", "merge.result", "end.result", "left.result\u0000"))
            rejects(graph(merge(ref, "right.result")));
    }

    @Test void rejectsDuplicateProducerAndOutOfRangeCandidateLists() {
        rejects(graph(merge("left.result", "left.result")));
        rejects(graph(merge("left.result", "left.other")));
        rejects(graph(merge()));
        rejects(graph(merge("left.result")));
        String[] many = new String[17];
        Arrays.fill(many, "left.result");
        rejects(graph(merge(many)));
    }

    @Test void newNodeRejectsUnknownConfigButOldNodesKeepExtensionFields() {
        var agg = merge("left.result", "right.result");
        ((com.fasterxml.jackson.databind.node.ObjectNode) agg.config()).put("fallback", "hidden");
        rejects(graph(agg));
        var old = diamond("{{start.userMessage}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) old.nodes().get(2).config()).put("historicalExtension", "retain");
        validator.validate(old);
    }

    @Test void rejectsKnowledgeListSourcesBeforeExecution() {
        var g = graph(merge("left.citations", "right.result"));
        var ns = new ArrayList<>(g.nodes());
        ns.set(2, node("left", "KNOWLEDGE", "knowledgeBaseId", "kb", "query", "q"));
        rejects(draft(ns, g.edges().toArray(WorkflowEdgeSpec[]::new)));
    }

    @Test void runtimePreservesFalseZeroEmptyAndExactNumbersWithoutEvaluatingTemplates() {
        var agg = merge("left.result", "right.result");
        for (Object value : List.of(false, 0, "", new BigDecimal("0.12345678901234567890123"), "{{secret.value}}"))
            assertThat(WorkflowAggregation.select(agg, Map.of("right.result", value))).isSameAs(value);
    }

    @Test void defensiveRuntimeMissingMultipleNullAndNonScalarAreRejected() {
        var agg = merge("left.result", "right.result");
        for (Map<String,Object> ctx : List.of(Map.<String,Object>of(), Map.<String,Object>of("left.result", "a", "right.result", "b"),
                Map.<String,Object>of("left.result", List.of("citation")), Map.<String,Object>of("left.result", Map.of("x", "y"))))
            assertThatThrownBy(() -> WorkflowAggregation.select(agg, ctx)).isInstanceOf(BizException.class);
        var nullContext = new HashMap<String,Object>();
        nullContext.put("left.result", null);
        assertThatThrownBy(() -> WorkflowAggregation.select(agg, nullContext)).isInstanceOf(BizException.class);
    }

    @Test void parallelLabelsToTheSamePredecessorDoNotDoubleCount() {
        var g = graph(merge("left.result", "right.result"));
        var es = new ArrayList<>(g.edges());
        es.add(new WorkflowEdgeSpec("also-right", "route", "right", "false", false));
        validator.validate(draft(g.nodes(), es.toArray(WorkflowEdgeSpec[]::new)));
    }

    @Test void oldGraphSerializationAndRawChecksumStayUnchanged() throws Exception {
        for (var g : List.of(chain(2), chain(50), diamond("{{start.userMessage}}"), condition("true"))) {
            validator.validate(g);
            String legacy = JSON.writeValueAsString(g);
            assertThat(WorkflowPublishedGraph.write(g, JSON)).isEqualTo(legacy);
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var version = new com.hify.workflow.domain.WorkflowVersion("v", "w", 1, 1, legacy, digest, java.time.Instant.now());
            validator.validate(WorkflowPublishedGraph.read(version, JSON));
            assertThat(version.getChecksum()).isEqualTo(digest);
            assertThat(version.getDslJson()).isEqualTo(legacy);
        }
    }

    @Test void acceptsNestedMergesButRejectsAReferenceBypassingThem() {
        var inner = merge("left.result", "right.result");
        var outerConfig = JSON.createObjectNode();
        outerConfig.putArray("candidates").add("merge.result").add("other.result");
        var outer = new WorkflowNodeSpec("outer", "AGGREGATOR", "outer", outerConfig);
        var g = graph(inner);
        var ns = new ArrayList<>(g.nodes());
        ns.add(node("first", "CONDITION", "expression", "true"));
        ns.add(node("other", "TEMPLATE", "template", "O"));
        ns.add(outer);
        ns.set(5, node("end", "END", "output", "{{outer.result}}"));
        var es = new ArrayList<>(g.edges());
        es.removeIf(e -> e.edgeKey().equals("start-route") || e.edgeKey().equals("merge-end"));
        es.add(edge("start", "first"));
        es.add(new WorkflowEdgeSpec("first-route", "first", "route", "true", false));
        es.add(new WorkflowEdgeSpec("first-other", "first", "other", null, true));
        es.add(edge("merge", "outer"));es.add(edge("other", "outer"));es.add(edge("outer", "end"));
        validator.validate(draft(ns, es.toArray(WorkflowEdgeSpec[]::new)));
        ns.set(5, node("end", "END", "output", "{{merge.result}}"));
        rejects(draft(ns, es.toArray(WorkflowEdgeSpec[]::new)));
    }
}
