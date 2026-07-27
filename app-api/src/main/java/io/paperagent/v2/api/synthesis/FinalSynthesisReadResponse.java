package io.paperagent.v2.api.synthesis;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, evidence-safe response from the Final Synthesis read facade.
 */
public final class FinalSynthesisReadResponse {
    private final FinalSynthesisReadResponseStatus status;
    private final Optional<FinalSynthesisReadModel> body;

    private FinalSynthesisReadResponse(
            FinalSynthesisReadResponseStatus status, Optional<FinalSynthesisReadModel> body) {
        this.status = Objects.requireNonNull(status, "status");
        this.body = Objects.requireNonNull(body, "body");
        if (status == FinalSynthesisReadResponseStatus.FOUND && body.isEmpty()) {
            throw new IllegalArgumentException("FOUND responses require a body");
        }
        if (status != FinalSynthesisReadResponseStatus.FOUND && body.isPresent()) {
            throw new IllegalArgumentException("only FOUND responses may contain a body");
        }
    }

    public static FinalSynthesisReadResponse found(FinalSynthesisReadModel body) {
        return of(FinalSynthesisReadResponseStatus.FOUND, Optional.of(Objects.requireNonNull(body, "body")));
    }

    public static FinalSynthesisReadResponse notFound() {
        return of(FinalSynthesisReadResponseStatus.NOT_FOUND, Optional.empty());
    }

    public static FinalSynthesisReadResponse invalidIdentifier() {
        return of(FinalSynthesisReadResponseStatus.INVALID_IDENTIFIER, Optional.empty());
    }

    static FinalSynthesisReadResponse of(
            FinalSynthesisReadResponseStatus status, Optional<FinalSynthesisReadModel> body) {
        return new FinalSynthesisReadResponse(status, body);
    }

    public FinalSynthesisReadResponseStatus status() {
        return status;
    }

    public Optional<FinalSynthesisReadModel> body() {
        return body;
    }

    @Override
    public String toString() {
        return "FinalSynthesisReadResponse[status=<provided>, body=<provided>]";
    }
}
