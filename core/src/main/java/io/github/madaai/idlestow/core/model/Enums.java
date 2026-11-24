package io.github.madaai.idlestow.core.model;

/** Holder for small shared enumerations. Kept together so contracts are easy to review. */
public final class Enums {
    private Enums() { }

    /** Activity classification of a file. Ordered from most to least active. */
    public enum ActivityClass {
        ACTIVE("Active"),
        PROBABLY_ACTIVE("Probably active"),
        POTENTIALLY_INACTIVE("Potentially inactive"),
        INACTIVE("Inactive (reliable evidence)"),
        UNKNOWN("Activity unknown"),
        PINNED("Pinned"),
        EXCLUDED("Excluded");

        private final String label;
        ActivityClass(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Confidence in an activity classification. Ordered weakest first. */
    public enum Confidence {
        NONE, LOW, MEDIUM, HIGH;

        public boolean atLeast(Confidence other) { return ordinal() >= other.ordinal(); }

        public static Confidence parse(String s, Confidence fallback) {
            if (s == null) return fallback;
            try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { return fallback; }
        }
    }

    /** The three authorization modes. ASK is the default. */
    public enum AuthorizationMode {
        MANUAL("Manual"), ASK("Ask before optimizing"), AUTONOMOUS("Fully autonomous");
        private final String label;
        AuthorizationMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Where an optimization request came from. */
    public enum Origin { MANUAL, APPROVAL, AUTONOMOUS, RECOVERY }

    /** Archive container formats produced by the Python worker. */
    public enum ArchiveFormat {
        ZIP("zip", ".idlestow.zip"), TAR_ZST("tar.zst", ".idlestow.tar.zst");
        private final String wire;
        private final String suffix;
        ArchiveFormat(String wire, String suffix) { this.wire = wire; this.suffix = suffix; }
        public String wire() { return wire; }
        public String suffix() { return suffix; }
        public static ArchiveFormat fromWire(String w) {
            for (ArchiveFormat f : values()) if (f.wire.equals(w)) return f;
            throw new IllegalArgumentException("Unknown archive format " + w);
        }
    }

    /** Optimization methods. */
    public enum Method {
        /** Filesystem-native transparent compression (path stays valid, opens normally). */
        NATIVE("Transparent native compression", true, false, null),
        /** Compressed ZIP copy next to the original; original untouched; consumes space. */
        ZIP_COPY("ZIP compressed copy (original kept)", false, false, ArchiveFormat.ZIP),
        /** Original replaced by a verified ZIP archive. Changes accessibility. */
        ZIP_REPLACE("Replace with verified ZIP archive", false, true, ArchiveFormat.ZIP),
        TZST_COPY("tar.zst compressed copy (original kept)", false, false, ArchiveFormat.TAR_ZST),
        TZST_REPLACE("Replace with verified tar.zst archive", false, true, ArchiveFormat.TAR_ZST);

        private final String label;
        private final boolean transparent;
        private final boolean replacesOriginal;
        private final ArchiveFormat format;

        Method(String label, boolean transparent, boolean replacesOriginal, ArchiveFormat format) {
            this.label = label;
            this.transparent = transparent;
            this.replacesOriginal = replacesOriginal;
            this.format = format;
        }
        public String label() { return label; }
        public boolean transparent() { return transparent; }
        public boolean replacesOriginal() { return replacesOriginal; }
        public boolean isArchive() { return format != null; }
        public ArchiveFormat format() { return format; }
    }

    /** Current optimization state of an indexed file. */
    public enum OptStatus {
        NONE("Not optimized"),
        NATIVE_COMPRESSED("Compressed (IdleStow)"),
        EXTERNALLY_COMPRESSED("Already compressed"),
        ARCHIVED("Archived (original replaced)"),
        ARCHIVE_COPY("Compressed copy exists"),
        NO_BENEFIT("No benefit"),
        FAILED("Failed"),
        RECOVERY_REQUIRED("Recovery required");
        private final String label;
        OptStatus(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Durable job states (see docs/architecture/recovery-state-machine.md). */
    public enum JobState {
        CREATED, AWAITING_APPROVAL, APPROVED, QUEUED, PREFLIGHT, RUNNING, VERIFYING, COMMITTING,
        COMPLETED, PAUSED, CANCELLED, FAILED, RECOVERY_REQUIRED, RESTORED;

        public boolean terminal() {
            return this == COMPLETED || this == CANCELLED || this == FAILED || this == RESTORED;
        }
    }

    public enum JobKind { OPTIMIZE, RESTORE, DECOMPRESS, VERIFY, PURGE_RETAINED, DELETE_COPY }

    /** Candidate / recommendation status. */
    public enum CandidateStatus { PENDING, APPROVED, REJECTED, POSTPONED, DISMISSED, DONE }

    public enum RecoveryKind { NATIVE, ARCHIVE_COPY, ARCHIVE_REPLACE }

    public enum RecoveryStatus { ACTIVE, RESTORED, ORIGINAL_PURGED, DAMAGED, MISSING, REVIEW, REMOVED }

    public enum CompressionProfile { FAST, BALANCED, MAXIMUM }

    public enum PerformanceMode { SILENT, BALANCED, PERFORMANCE }

    public enum CloseBehavior { EXIT, TRAY }
}
