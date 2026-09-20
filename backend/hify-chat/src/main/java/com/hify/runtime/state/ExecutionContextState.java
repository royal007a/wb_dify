package com.hify.runtime.state;

import com.hify.runtime.ToolRuntime;
import com.hify.runtime.ToolEvidencePayload;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.plan.StepAttempt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public record ExecutionContextState(
        long evidenceVersion,
        long gapVersion,
        List<ClaimState> claims,
        List<EvidenceItem> evidence,
        List<GapState> gaps,
        String lastSemanticFingerprint,
        int repeatedStateCount
) {
    public ExecutionContextState {
        if (evidenceVersion < 0 || gapVersion < 0) throw new IllegalArgumentException("State versions cannot be negative");
        claims = List.copyOf(claims == null ? List.of() : claims);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        gaps = List.copyOf(gaps == null ? List.of() : gaps);
        if (repeatedStateCount < 0) throw new IllegalArgumentException("Repeated state count cannot be negative");
    }

    public static ExecutionContextState empty() {
        return new ExecutionContextState(0, 0, List.of(), List.of(), List.of(), null, 0);
    }

    public ExecutionContextState recordToolResult(ExecutionPlan plan, StepAttempt attempt,
                                                  ToolRuntime.ExecutionResult result) {
        String claimId = UUID.randomUUID().toString();
        EvidenceItem item = EvidenceItem.toolResult(claimId, attempt.toolCallId(), attempt.toolName(),
                attempt.id(), result.value(), result.failureType().name(), plan.version());
        boolean navigation = result.value() instanceof ToolEvidencePayload payload
                && payload.evidenceKind() == ToolEvidencePayload.EvidenceKind.NAVIGATION;
        ClaimState claim = new ClaimState(claimId,
                result.error() ? "Tool attempt failed: " + attempt.toolName()
                        : "Tool produced a result: " + attempt.toolName(),
                ClaimState.Kind.FACT,
                navigation ? ClaimState.Status.UNVERIFIED : ClaimState.Status.VERIFIED,
                !result.error() && !navigation, List.of(item.id()));
        List<ClaimState> nextClaims = new ArrayList<>(claims);
        nextClaims.add(claim);
        List<EvidenceItem> nextEvidence = new ArrayList<>(evidence);
        nextEvidence.add(item);
        ExecutionContextState next = new ExecutionContextState(evidenceVersion + 1, gapVersion,
                nextClaims, nextEvidence, gaps, lastSemanticFingerprint, repeatedStateCount);
        if (navigation) {
            return next.openGap(GapState.open(GapState.Kind.UNVERIFIED_CLAIM,
                    "History search returned navigation candidates; canonical detail is still required",
                    true, "tool:history.detail", List.of("call_history_detail", "clarify"),
                    attempt.id()));
        }
        return result.error() ? next : next.resolveGaps("tool:" + attempt.toolName());
    }

    public ExecutionContextState openGap(GapState gap) {
        boolean duplicate = gaps.stream().anyMatch(existing -> existing.status() == GapState.Status.OPEN
                && existing.kind() == gap.kind()
                && existing.resolutionKey().equals(gap.resolutionKey())
                && existing.description().equals(gap.description()));
        if (duplicate) return this;
        List<GapState> next = new ArrayList<>(gaps);
        next.add(gap);
        return new ExecutionContextState(evidenceVersion, gapVersion + 1, claims, evidence,
                next, lastSemanticFingerprint, repeatedStateCount);
    }

    public ExecutionContextState resolveGaps(String resolutionKey) {
        boolean changed = false;
        List<GapState> next = new ArrayList<>(gaps.size());
        for (GapState gap : gaps) {
            if (gap.status() == GapState.Status.OPEN && gap.resolutionKey().equals(resolutionKey)) {
                next.add(gap.resolve());
                changed = true;
            } else {
                next.add(gap);
            }
        }
        return changed ? new ExecutionContextState(evidenceVersion, gapVersion + 1, claims,
                evidence, next, lastSemanticFingerprint, repeatedStateCount) : this;
    }

    public ExecutionContextState resolveGapsWithUserInput(List<String> gapIds, String input,
                                                          int planVersion, String sourceRunId) {
        if (gapIds == null || gapIds.isEmpty()) throw new IllegalArgumentException("At least one gap id is required");
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Gap resolution input is required");
        List<GapState> selected = gaps.stream().filter(gap -> gapIds.contains(gap.id())
                && gap.status() == GapState.Status.OPEN).toList();
        if (selected.size() != gapIds.stream().distinct().count()) {
            throw new IllegalArgumentException("Gap resolution references an unknown or closed gap");
        }

        String claimId = UUID.randomUUID().toString();
        EvidenceItem item = EvidenceItem.userInput(claimId, sourceRunId, input, planVersion);
        ClaimState claim = new ClaimState(claimId, "User supplied requested information",
                ClaimState.Kind.FACT, ClaimState.Status.VERIFIED, false, List.of(item.id()));
        List<ClaimState> nextClaims = new ArrayList<>(claims);
        nextClaims.add(claim);
        List<EvidenceItem> nextEvidence = new ArrayList<>(evidence);
        nextEvidence.add(item);
        List<GapState> nextGaps = gaps.stream()
                .map(gap -> gapIds.contains(gap.id()) ? gap.resolve() : gap).toList();
        return new ExecutionContextState(evidenceVersion + 1, gapVersion + 1,
                nextClaims, nextEvidence, nextGaps, null, 0);
    }

    public ExecutionContextState observeProgress() {
        String fingerprint = semanticFingerprint();
        int repeated = fingerprint.equals(lastSemanticFingerprint) ? repeatedStateCount + 1 : 1;
        return new ExecutionContextState(evidenceVersion, gapVersion, claims, evidence, gaps,
                fingerprint, repeated);
    }

    public boolean hasNoProgress(int repeatedStateThreshold) {
        return repeatedStateCount >= repeatedStateThreshold;
    }

    public List<String> evidenceIds() {
        return evidence.stream().map(EvidenceItem::id).toList();
    }

    public List<GapState> openBlockingGaps() {
        return gaps.stream().filter(gap -> gap.blocking() && gap.status() == GapState.Status.OPEN).toList();
    }

    public List<String> openBlockingGapIds() {
        return openBlockingGaps().stream().map(GapState::id).toList();
    }

    public boolean hasVerifiedCoverageForRequiredClaims() {
        return claims.stream().filter(ClaimState::requiredForCompletion).allMatch(claim ->
                claim.status() == ClaimState.Status.VERIFIED && !claim.evidenceIds().isEmpty()
                        && claim.evidenceIds().stream().anyMatch(evidenceId -> evidence.stream().anyMatch(item ->
                        item.id().equals(evidenceId) && item.claimId().equals(claim.id())
                                && item.status() == EvidenceItem.Status.VERIFIED)));
    }

    public String semanticFingerprint() {
        String verified = evidence.stream().filter(item -> item.status() == EvidenceItem.Status.VERIFIED)
                .map(EvidenceItem::valueDigest).distinct().sorted().reduce("", (left, right) -> left + "|" + right);
        String open = gaps.stream().filter(gap -> gap.status() == GapState.Status.OPEN)
                .sorted(Comparator.comparing(GapState::id))
                .map(gap -> gap.kind() + ":" + gap.resolutionKey() + ":" + gap.description())
                .distinct().sorted().reduce("", (left, right) -> left + "|" + right);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((verified + "\n" + open).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not fingerprint execution state", exception);
        }
    }
}
