package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;

final class StepActivationCompositionValues {
    private StepActivationCompositionValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(
                    StepActivationCompositionValidationCode.REQUIRED_VALUE_MISSING,
                    path);
        }
        return value;
    }

    static String identifier(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            throw failure(
                    StepActivationCompositionValidationCode.INVALID_IDENTIFIER,
                    path);
        }
        return value;
    }

    static StepActivationCompositionValidationException failure(
            StepActivationCompositionValidationCode code,
            String path) {
        return new StepActivationCompositionValidationException(
                requiredInternal(code, "code"),
                requiredInternal(path, "path"));
    }

    static StepActivationCompositionProtocolException protocolFailure(
            PlanId planId,
            StepActivationCompositionStage stage,
            StepActivationCompositionProtocolCode code,
            String path,
            StepActivationLeaseDisposition leaseDisposition,
            Throwable cause) {
        return new StepActivationCompositionProtocolException(
                requiredInternal(planId, "planId"),
                requiredInternal(stage, "stage"),
                requiredInternal(code, "code"),
                requiredInternal(path, "path"),
                requiredInternal(leaseDisposition, "leaseDisposition"),
                cause);
    }

    static void requireCommitted(
            PersistenceOutcome outcome,
            PersistedStepActivation persisted,
            StepActivationLeaseDisposition disposition) {
        required(outcome, "stepActivationCommitted.activationOutcome");
        if (outcome != PersistenceOutcome.APPLIED
                && outcome != PersistenceOutcome.REPLAYED) {
            throw invalidOutcome("stepActivationCommitted.activationOutcome");
        }
        required(persisted, "stepActivationCommitted.persistedActivation");
        requireDisposition(
                disposition,
                "stepActivationCommitted.leaseDisposition",
                StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static void requireLeaseRejected(
            PlanId planId,
            PersistenceFailure failure,
            StepActivationLeaseDisposition disposition) {
        required(planId, "stepActivationLeaseRejected.planId");
        required(failure, "stepActivationLeaseRejected.failure");
        requireDisposition(
                disposition,
                "stepActivationLeaseRejected.leaseDisposition",
                StepActivationLeaseDisposition.NOT_ACQUIRED);
    }

    static void requirePersistenceRejected(
            PlanId planId,
            PersistenceFailure failure,
            StepActivationLeaseDisposition disposition) {
        required(planId, "stepActivationPersistenceRejected.planId");
        required(failure, "stepActivationPersistenceRejected.failure");
        requireDisposition(
                disposition,
                "stepActivationPersistenceRejected.leaseDisposition",
                StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static String validationPath(String path) {
        return requiredInternal(path, "path");
    }

    static String protocolPath(String path) {
        return requiredInternal(path, "path");
    }

    static <T> T requiredInternal(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static void requireDisposition(
            StepActivationLeaseDisposition actual,
            String path,
            StepActivationLeaseDisposition expected) {
        required(actual, path);
        if (actual != expected) {
            throw invalidOutcome(path);
        }
    }

    private static StepActivationCompositionValidationException invalidOutcome(
            String path) {
        return failure(
                StepActivationCompositionValidationCode.INVALID_OUTCOME_STATE,
                path);
    }
}
