package io.github.madaai.idlestow.core.jobs;

/** Result of one file operation. {@code savings} is a measured value (bytes), never an estimate. */
public record OpOutcome(Result result, String message, long savings, long bytesProcessed) {

    public enum Result { SUCCEEDED, NO_BENEFIT, SKIPPED, FAILED, CANCELLED, RECOVERY_REQUIRED, CONFLICT }

    public static OpOutcome ok(String message, long savings, long bytes) {
        return new OpOutcome(Result.SUCCEEDED, message, savings, bytes);
    }

    public static OpOutcome failed(String message) {
        return new OpOutcome(Result.FAILED, message, 0, 0);
    }

    public static OpOutcome skipped(String message) {
        return new OpOutcome(Result.SKIPPED, message, 0, 0);
    }
}
