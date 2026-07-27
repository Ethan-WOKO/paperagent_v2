package io.paperagent.v2.api.synthesis;

import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import java.util.Optional;

/**
 * Read-only source boundary for immutable final synthesis candidates.
 */
public interface FinalSynthesisReadSource {
    Optional<FinalSynthesis> find(FinalSynthesisId synthesisId);
}
