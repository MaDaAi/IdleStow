package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.jobs.FaultInjector;
import io.github.madaai.idlestow.core.platform.PlatformServices;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Construction parameters for {@link IdleStowEngine}.
 *
 * @param readOnly when true the engine indexes and recommends but refuses every filesystem mutation
 */
public record EngineConfig(Path dataDir, PlatformServices platform, OptimizationWorker worker, Clock clock,
                           boolean readOnly, FaultInjector faults) {
}
