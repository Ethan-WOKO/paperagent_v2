package io.paperagent.v2.api.synthesis;

import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only query use case that exposes a final synthesis only as its safe summary.
 */
public final class FinalSynthesisReadService {
    private final FinalSynthesisReadSource source;

    public FinalSynthesisReadService(FinalSynthesisReadSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public Optional<FinalSynthesisReadModel> find(FinalSynthesisId synthesisId) {
        Objects.requireNonNull(synthesisId, "synthesisId");
        Optional<FinalSynthesis> candidate = Objects.requireNonNull(
                source.find(synthesisId), "source.find(synthesisId)");
        return candidate.map(synthesis -> {
            if (!synthesis.id().equals(synthesisId)) {
                throw new IllegalStateException("source returned a synthesis with a different id");
            }
            return FinalSynthesisReadModel.from(synthesis);
        });
    }
}
