package io.paperagent.v2.api.synthesis;

import io.paperagent.v2.contracts.FinalSynthesisDecisionAction;
import io.paperagent.v2.contracts.FinalSynthesisDecisionIntent;
import io.paperagent.v2.contracts.FinalSynthesisId;
import java.util.Objects;

/**
 * Maps raw API input to the untrusted Final Synthesis decision-intent contract.
 */
public final class FinalSynthesisDecisionIntentIntake {
    public FinalSynthesisDecisionIntent intake(FinalSynthesisDecisionIntentRequest request) {
        Objects.requireNonNull(request, "request");
        return new FinalSynthesisDecisionIntent(
                request.decisionId(),
                new FinalSynthesisId(request.finalSynthesisId()),
                FinalSynthesisDecisionAction.valueOf(request.action()),
                request.reason(),
                request.requestedAt());
    }
}
