package io.github.madaai.idlestow.platform;

import io.github.madaai.idlestow.core.platform.PlatformServices;
import io.github.madaai.idlestow.core.platform.PortablePlatform;
import io.github.madaai.idlestow.platform.linux.LinuxPlatform;
import io.github.madaai.idlestow.platform.macos.MacPlatform;
import io.github.madaai.idlestow.platform.windows.WindowsPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** Chooses the {@link PlatformServices} implementation for the running operating system. */
public final class Platforms {

    private static final Logger LOG = LoggerFactory.getLogger(Platforms.class);

    private Platforms() { }

    /** Never throws: any failure (for example JNA not loadable) falls back to {@link PortablePlatform}. */
    public static PlatformServices detect() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) return new WindowsPlatform();
            if (os.contains("mac") || os.contains("darwin")) return new MacPlatform();
            if (os.contains("linux")) return new LinuxPlatform();
        } catch (Throwable t) { // includes UnsatisfiedLinkError / NoClassDefFoundError from JNA
            LOG.warn("Platform adapter failed to initialize, falling back to portable implementation", t);
        }
        return new PortablePlatform();
    }
}
