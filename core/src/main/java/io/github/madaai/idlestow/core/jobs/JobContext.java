package io.github.madaai.idlestow.core.jobs;

import io.github.madaai.idlestow.core.db.CandidateRepository;
import io.github.madaai.idlestow.core.db.HistoryRepository;
import io.github.madaai.idlestow.core.db.ItemRepository;
import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.db.RootRepository;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.platform.PlatformServices;
import io.github.madaai.idlestow.core.policy.PolicyEngine;
import io.github.madaai.idlestow.core.settings.SettingsStore;

import java.nio.file.Path;
import java.time.Clock;

/** Dependencies shared by the job engine. */
public record JobContext(
        PlatformServices platform,
        OptimizationWorker worker,
        ItemRepository items,
        RootRepository roots,
        PolicyEngine policy,
        Authorizer authorizer,
        JobRepository jobs,
        HistoryRepository history,
        RecoveryRepository recovery,
        CandidateRepository candidates,
        SettingsStore settings,
        Path dataDir,
        Clock clock,
        FaultInjector faults) {

    public Path manifestDir() {
        return dataDir.resolve("recovery").resolve("manifests");
    }
}
