package io.paperagent.v2.api.synthesis;

import java.time.Instant;
import java.util.Optional;

/**
 * Immutable raw input for one untrusted Final Synthesis decision intent.
 *
 * <p>The strings remain opaque at this API boundary. The intake maps them through the stable
 * contracts boundary.</p>
 */
public record FinalSynthesisDecisionIntentRequest(
        String decisionId,
        String finalSynthesisId,
        String action,
        Optional<String> reason,
        Instant requestedAt) {

    public FinalSynthesisDecisionIntentRequest {
        if (reason != null && reason.isPresent()) {
            reason = Optional.of(reason.get());
        }
    }

    @Override
    public String toString() {
        return "FinalSynthesisDecisionIntentRequest["
                + "decisionId=<provided>, "
                + "finalSynthesisId=<provided>, "
                + "action=<provided>, "
                + "reason=<provided>, "
                + "requestedAt=<provided>]";
    }
}
