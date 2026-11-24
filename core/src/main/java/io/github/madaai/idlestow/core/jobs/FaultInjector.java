package io.github.madaai.idlestow.core.jobs;

/**
 * Test hook invoked at every significant step of an operation. Production uses {@link #NONE}.
 * Tests throw {@link SimulatedCrash} (an {@link Error}, deliberately not caught by the executor) to
 * emulate a process crash at an exact step, then run crash recovery.
 */
@FunctionalInterface
public interface FaultInjector {
    void at(String step);

    FaultInjector NONE = step -> { };

    /** Emulates abrupt termination: executor cleanup code does not run. */
    final class SimulatedCrash extends Error {
        public SimulatedCrash(String step) {
            super("Simulated crash at " + step);
        }
    }
}
