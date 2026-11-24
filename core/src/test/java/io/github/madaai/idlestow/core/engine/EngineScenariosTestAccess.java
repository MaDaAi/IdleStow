package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.ipc.OptimizationWorker;

/** Exposes test helpers from {@link EngineScenariosTest} to other test packages. */
public final class EngineScenariosTestAccess {
    private EngineScenariosTestAccess() { }

    public static OptimizationWorker nonClosing(OptimizationWorker w) {
        return new EngineScenariosTest.NonClosingWorker(w);
    }
}
