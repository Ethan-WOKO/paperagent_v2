package io.paperagent.v2.api.synthesis;

import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.FinalSynthesisId;
import java.util.Objects;

/**
 * Framework-free facade for the safe, read-only Final Synthesis summary.
 */
public final class FinalSynthesisReadEndpoint {
    private final FinalSynthesisReadService service;

    public FinalSynthesisReadEndpoint(FinalSynthesisReadService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public FinalSynthesisReadResponse read(String rawSynthesisId) {
        FinalSynthesisId synthesisId;
        try {
            synthesisId = new FinalSynthesisId(rawSynthesisId);
        } catch (ContractViolationException exception) {
            return FinalSynthesisReadResponse.invalidIdentifier();
        }
        return service.find(synthesisId)
                .map(FinalSynthesisReadResponse::found)
                .orElseGet(FinalSynthesisReadResponse::notFound);
    }
}
