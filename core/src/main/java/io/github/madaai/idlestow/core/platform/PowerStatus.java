package io.github.madaai.idlestow.core.platform;

import java.util.Optional;

/** Power state used to pause background work on battery. */
public interface PowerStatus {
    /** {@code Optional.of(true)} when running on battery, empty when unknown. */
    Optional<Boolean> onBattery();

    PowerStatus UNKNOWN = Optional::empty;
}
