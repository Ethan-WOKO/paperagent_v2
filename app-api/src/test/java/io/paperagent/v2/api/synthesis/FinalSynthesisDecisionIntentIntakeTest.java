package io.paperagent.v2.api.synthesis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.FinalSynthesisDecisionAction;
import io.paperagent.v2.contracts.FinalSynthesisDecisionIntent;
import io.paperagent.v2.contracts.ViolationCode;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FinalSynthesisDecisionIntentIntakeTest {
    private static final String DECISION_ID = "api-decision-sentinel";
    private static final String SYNTHESIS_ID = "api-synthesis-sentinel";
    private static final String REASON = "api-reason-sentinel";
    private static final Instant REQUESTED_AT = Instant.parse("2026-07-27T18:00:00Z");

    @Test
    void mapsEveryRawRequestFieldToExactlyOneUntrustedIntent() {
        FinalSynthesisDecisionIntentIntake intake = new FinalSynthesisDecisionIntentIntake();

        FinalSynthesisDecisionIntent intent = intake.intake(request("ACCEPT", Optional.of(REASON)));

        assertEquals(DECISION_ID, intent.decisionId());
        assertEquals(SYNTHESIS_ID, intent.finalSynthesisId().value());
        assertEquals(FinalSynthesisDecisionAction.ACCEPT, intent.action());
        assertEquals(Optional.of(REASON), intent.reason());
        assertEquals(REQUESTED_AT, intent.requestedAt());
    }

    @Test
    void copiesOptionalReasonAndRedactsEveryRawRequestValue() {
        Optional<String> sourceReason = Optional.of(REASON);
        FinalSynthesisDecisionIntentRequest request = request("REJECT", sourceReason);

        assertNotSame(sourceReason, request.reason());
        assertEquals(Optional.of(REASON), request.reason());
        assertEquals(
                "FinalSynthesisDecisionIntentRequest[decisionId=<provided>, "
                        + "finalSynthesisId=<provided>, action=<provided>, reason=<provided>, "
                        + "requestedAt=<provided>]",
                request.toString());
        for (String sentinel : Set.of(DECISION_ID, SYNTHESIS_ID, "REJECT", REASON, REQUESTED_AT.toString())) {
            assertFalse(request.toString().contains(sentinel), sentinel);
        }
    }

    @Test
    void propagatesNullRequestAndContractValidationFailuresWithoutAnApiStatusOrEffect() {
        FinalSynthesisDecisionIntentIntake intake = new FinalSynthesisDecisionIntentIntake();

        NullPointerException nullRequest = assertThrows(NullPointerException.class, () -> intake.intake(null));
        assertEquals("request", nullRequest.getMessage());
        assertViolation(
                () -> intake.intake(new FinalSynthesisDecisionIntentRequest(
                        DECISION_ID, "invalid synthesis id", "ACCEPT", Optional.empty(), REQUESTED_AT)),
                ViolationCode.INVALID_ID,
                "finalSynthesisId");
        assertViolation(
                () -> intake.intake(new FinalSynthesisDecisionIntentRequest(
                        "invalid decision id", SYNTHESIS_ID, "ACCEPT", Optional.empty(), REQUESTED_AT)),
                ViolationCode.INVALID_ID,
                "finalSynthesisDecisionIntent.decisionId");
        assertViolation(
                () -> intake.intake(new FinalSynthesisDecisionIntentRequest(
                        DECISION_ID, SYNTHESIS_ID, "REJECT", Optional.of(" "), REQUESTED_AT)),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "finalSynthesisDecisionIntent.reason");
        assertViolation(
                () -> intake.intake(new FinalSynthesisDecisionIntentRequest(
                        DECISION_ID, SYNTHESIS_ID, "REJECT", null, REQUESTED_AT)),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesisDecisionIntent.reason");
        assertThrows(IllegalArgumentException.class,
                () -> intake.intake(request("UNRECOGNIZED", Optional.empty())));
    }

    @Test
    void keepsTheExactInputAndOneOperationIntakeSurface() {
        assertTrue(FinalSynthesisDecisionIntentRequest.class.isRecord());
        RecordComponent[] components = FinalSynthesisDecisionIntentRequest.class.getRecordComponents();
        assertEquals(
                List.of("decisionId", "finalSynthesisId", "action", "reason", "requestedAt"),
                Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(
                List.of(String.class, String.class, String.class, Optional.class, Instant.class),
                Arrays.stream(components).map(RecordComponent::getType).toList());
        assertEquals(
                Set.of("action", "decisionId", "equals", "finalSynthesisId", "hashCode", "reason",
                        "requestedAt", "toString"),
                Arrays.stream(FinalSynthesisDecisionIntentRequest.class.getDeclaredMethods())
                        .map(Method::getName)
                        .collect(Collectors.toSet()));

        assertTrue(Modifier.isPublic(FinalSynthesisDecisionIntentIntake.class.getModifiers()));
        assertTrue(Modifier.isFinal(FinalSynthesisDecisionIntentIntake.class.getModifiers()));
        assertEquals(Set.of("intake"), Arrays.stream(FinalSynthesisDecisionIntentIntake.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet()));
        assertEquals(0, FinalSynthesisDecisionIntentIntake.class.getDeclaredFields().length);
    }

    @Test
    void keepsImportsAndOuterConcernsOutsideTheFrameworkFreeAdapter() throws IOException {
        List<Path> productionSources = List.of(
                Path.of("src/main/java/io/paperagent/v2/api/synthesis/FinalSynthesisDecisionIntentRequest.java"),
                Path.of("src/main/java/io/paperagent/v2/api/synthesis/FinalSynthesisDecisionIntentIntake.java"));
        for (Path sourceFile : productionSources) {
            String source = Files.readString(sourceFile);
            for (String importedType : source.lines()
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.substring("import ".length(), line.length() - 1))
                    .toList()) {
                assertTrue(importedType.startsWith("java.")
                        || importedType.startsWith("io.paperagent.v2.contracts."), importedType);
            }
            for (String forbidden : List.of(
                    "io.paperagent.v1",
                    "agent-runtime",
                    "agent-persistence",
                    "agent-workspace",
                    "agent-sandbox",
                    "agent-providers",
                    "spring",
                    "javax.",
                    "jakarta.",
                    "controller",
                    "repository",
                    "http",
                    "network",
                    "ProjectVersion",
                    "paperagent_redo")) {
                assertFalse(source.contains(forbidden), sourceFile + ": " + forbidden);
            }
        }
    }

    private static FinalSynthesisDecisionIntentRequest request(String action, Optional<String> reason) {
        return new FinalSynthesisDecisionIntentRequest(DECISION_ID, SYNTHESIS_ID, action, reason, REQUESTED_AT);
    }

    private static void assertViolation(Runnable action, ViolationCode code, String path) {
        ContractViolationException exception = assertThrows(ContractViolationException.class, action::run);
        assertEquals(code, exception.primaryCode());
        assertEquals(path, exception.violations().get(0).path());
    }
}
