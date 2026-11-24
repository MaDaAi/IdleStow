-- IdleStow schema version 1. All *_utc columns are ISO-8601 UTC strings; *_ms columns are epoch milliseconds.

CREATE TABLE settings (
    key          TEXT PRIMARY KEY,
    value        TEXT NOT NULL,
    updated_utc  TEXT NOT NULL
);

CREATE TABLE filesystem_volumes (
    volume_id           TEXT PRIMARY KEY,
    root_path           TEXT,
    fs_type             TEXT,
    native_compression  INTEGER NOT NULL DEFAULT 0,
    native_note         TEXT,
    cluster_size        INTEGER,
    read_only           INTEGER NOT NULL DEFAULT 0,
    remote              INTEGER NOT NULL DEFAULT 0,
    access_tracking     TEXT NOT NULL DEFAULT 'UNKNOWN',
    access_note         TEXT,
    observed_utc        TEXT NOT NULL
);

CREATE TABLE monitored_roots (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    path              TEXT NOT NULL,
    path_key          TEXT NOT NULL UNIQUE,
    display_name      TEXT NOT NULL,
    volume_id         TEXT,
    enabled           INTEGER NOT NULL DEFAULT 1,
    autonomy_enabled  INTEGER NOT NULL DEFAULT 0,
    archival          INTEGER NOT NULL DEFAULT 0,
    authorized_utc    TEXT NOT NULL,
    revoked_utc       TEXT,
    last_scan_utc     TEXT,
    last_scan_status  TEXT,
    scan_generation   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE indexed_items (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    root_id              INTEGER NOT NULL REFERENCES monitored_roots(id) ON DELETE CASCADE,
    path                 TEXT NOT NULL,
    path_key             TEXT NOT NULL UNIQUE,
    parent_key           TEXT,
    rel_path             TEXT NOT NULL,
    name                 TEXT NOT NULL,
    name_lc              TEXT NOT NULL,
    extension            TEXT,
    category             TEXT,
    is_dir               INTEGER NOT NULL,
    present              INTEGER NOT NULL DEFAULT 1,
    special              INTEGER NOT NULL DEFAULT 0,
    file_key             TEXT,
    volume_id            TEXT,
    logical_size         INTEGER NOT NULL DEFAULT 0,
    physical_size        INTEGER NOT NULL DEFAULT -1,
    physical_exact       INTEGER NOT NULL DEFAULT 0,
    created_ms           INTEGER,
    modified_ms          INTEGER,
    accessed_ms          INTEGER,
    trusted_access_ms    INTEGER,
    first_indexed_utc    TEXT NOT NULL,
    last_scanned_utc     TEXT NOT NULL,
    last_changed_utc     TEXT,
    link_count           INTEGER NOT NULL DEFAULT -1,
    alt_streams          INTEGER NOT NULL DEFAULT -1,
    attr_flags           TEXT NOT NULL DEFAULT '',
    native_compressed    INTEGER NOT NULL DEFAULT 0,
    opt_status           TEXT NOT NULL DEFAULT 'NONE',
    est_zip_ratio        REAL,
    est_ntfs_savings     INTEGER,
    est_zip_savings      INTEGER,
    est_zstd_savings     INTEGER,
    est_format           TEXT,
    est_basis            TEXT,
    est_for_size         INTEGER,
    est_for_modified_ms  INTEGER,
    last_operation_id    INTEGER,
    error                TEXT,
    scan_generation      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_items_parent ON indexed_items(parent_key);
CREATE INDEX ix_items_root ON indexed_items(root_id, scan_generation);
CREATE INDEX ix_items_filekey ON indexed_items(file_key);
CREATE INDEX ix_items_status ON indexed_items(opt_status);
CREATE INDEX ix_items_name ON indexed_items(name_lc);
CREATE INDEX ix_items_size ON indexed_items(logical_size);

CREATE TABLE activity_evidence (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id       INTEGER NOT NULL REFERENCES indexed_items(id) ON DELETE CASCADE,
    kind          TEXT NOT NULL,          -- MODIFIED_OBSERVED, OPENED_VIA_IDLESTOW, SELF_READ, USER_DECLARED_ACTIVE, USER_CONFIRMED_INACTIVE, CREATED_OBSERVED
    event_ms      INTEGER NOT NULL,
    end_ms        INTEGER,
    detail        TEXT,
    recorded_utc  TEXT NOT NULL
);
CREATE INDEX ix_evidence_item ON activity_evidence(item_id, kind, event_ms);

CREATE TABLE activity_classifications (
    item_id           INTEGER PRIMARY KEY REFERENCES indexed_items(id) ON DELETE CASCADE,
    classification    TEXT NOT NULL,
    confidence        TEXT NOT NULL,
    last_activity_ms  INTEGER,
    idle_days         INTEGER,
    explanation       TEXT NOT NULL,
    evaluated_utc     TEXT NOT NULL
);
CREATE INDEX ix_class_class ON activity_classifications(classification);

CREATE TABLE directory_aggregates (
    path_key            TEXT PRIMARY KEY,
    root_id             INTEGER NOT NULL REFERENCES monitored_roots(id) ON DELETE CASCADE,
    total_files         INTEGER NOT NULL,
    total_dirs          INTEGER NOT NULL,
    active_files        INTEGER NOT NULL,
    inactive_files      INTEGER NOT NULL,
    unknown_files       INTEGER NOT NULL,
    excluded_files      INTEGER NOT NULL,
    optimized_files     INTEGER NOT NULL,
    logical_bytes       INTEGER NOT NULL,
    physical_bytes      INTEGER NOT NULL,
    est_reclaimable     INTEGER NOT NULL,
    actual_reclaimed    INTEGER NOT NULL,
    computed_utc        TEXT NOT NULL
);

CREATE TABLE exclusions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    kind         TEXT NOT NULL,   -- PATH, GLOB, EXTENSION, MIN_SIZE, MAX_SIZE, CATEGORY, VOLUME
    value        TEXT NOT NULL,
    created_utc  TEXT NOT NULL,
    UNIQUE(kind, value)
);

CREATE TABLE pinned_items (
    path_key     TEXT PRIMARY KEY,
    path         TEXT NOT NULL,
    created_utc  TEXT NOT NULL
);

CREATE TABLE policies (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    scope             TEXT NOT NULL,   -- GLOBAL, ROOT, DIRECTORY, FILE, EXTENSION
    target            TEXT NOT NULL,   -- path_key, extension, or '*'
    inactivity_days   INTEGER,
    never_optimize    INTEGER,
    archival          INTEGER,
    min_confidence    TEXT,
    allowed_methods   TEXT,
    updated_utc       TEXT NOT NULL,
    UNIQUE(scope, target)
);

CREATE TABLE optimization_candidates (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id             INTEGER NOT NULL UNIQUE REFERENCES indexed_items(id) ON DELETE CASCADE,
    method              TEXT NOT NULL,
    est_savings         INTEGER NOT NULL,
    est_basis           TEXT NOT NULL,
    reason              TEXT NOT NULL,
    policy_ref          TEXT NOT NULL,
    classification      TEXT NOT NULL,
    confidence          TEXT NOT NULL,
    warnings            TEXT NOT NULL DEFAULT '',
    auto_eligible       INTEGER NOT NULL DEFAULT 0,
    status              TEXT NOT NULL DEFAULT 'PENDING',
    postponed_until_ms  INTEGER,
    snapshot_size       INTEGER NOT NULL,
    snapshot_mtime      INTEGER NOT NULL,
    snapshot_key        TEXT,
    created_utc         TEXT NOT NULL,
    updated_utc         TEXT NOT NULL
);
CREATE INDEX ix_cand_status ON optimization_candidates(status);

CREATE TABLE optimization_plans (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    method              TEXT NOT NULL,
    origin              TEXT NOT NULL,
    authorization_mode  TEXT NOT NULL,
    policy_version      INTEGER NOT NULL,
    status              TEXT NOT NULL,   -- DRAFT, APPROVED, EXECUTED, STALE, CANCELLED
    item_count          INTEGER NOT NULL,
    est_savings         INTEGER NOT NULL,
    created_utc         TEXT NOT NULL
);

CREATE TABLE plan_items (
    plan_id         INTEGER NOT NULL REFERENCES optimization_plans(id) ON DELETE CASCADE,
    item_id         INTEGER NOT NULL,
    path            TEXT NOT NULL,
    snapshot_size   INTEGER NOT NULL,
    snapshot_mtime  INTEGER NOT NULL,
    snapshot_key    TEXT,
    PRIMARY KEY (plan_id, item_id)
);

CREATE TABLE approvals (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id         INTEGER NOT NULL UNIQUE REFERENCES optimization_plans(id) ON DELETE CASCADE,
    approved_utc    TEXT NOT NULL,
    plan_digest     TEXT NOT NULL,
    expires_utc     TEXT NOT NULL,
    consumed_utc    TEXT
);

CREATE TABLE jobs (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id           INTEGER,
    kind              TEXT NOT NULL,
    method            TEXT,
    origin            TEXT NOT NULL,
    state             TEXT NOT NULL,
    created_utc       TEXT NOT NULL,
    started_utc       TEXT,
    finished_utc      TEXT,
    total_items       INTEGER NOT NULL DEFAULT 0,
    done_items        INTEGER NOT NULL DEFAULT 0,
    failed_items      INTEGER NOT NULL DEFAULT 0,
    total_bytes       INTEGER NOT NULL DEFAULT 0,
    done_bytes        INTEGER NOT NULL DEFAULT 0,
    message           TEXT,
    cancel_requested  INTEGER NOT NULL DEFAULT 0,
    pause_requested   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_jobs_state ON jobs(state);

CREATE TABLE job_operations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid            TEXT NOT NULL UNIQUE,
    job_id          INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    item_id         INTEGER,
    path            TEXT NOT NULL,
    root_path       TEXT,
    action          TEXT NOT NULL,
    method          TEXT,
    state           TEXT NOT NULL,
    policy_version  INTEGER,
    snapshot        TEXT,
    before_state    TEXT,
    after_state     TEXT,
    verification    TEXT,
    recovery        TEXT,
    started_utc     TEXT,
    finished_utc    TEXT,
    error           TEXT
);
CREATE INDEX ix_ops_job ON job_operations(job_id);
CREATE INDEX ix_ops_state ON job_operations(state);

CREATE TABLE operation_journal (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_id  INTEGER NOT NULL REFERENCES job_operations(id) ON DELETE CASCADE,
    step          TEXT NOT NULL,
    at_utc        TEXT NOT NULL,
    data          TEXT
);
CREATE INDEX ix_journal_op ON operation_journal(operation_id);

CREATE TABLE optimization_history (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_id        INTEGER,
    at_utc              TEXT NOT NULL,
    path                TEXT NOT NULL,
    file_key            TEXT,
    action              TEXT NOT NULL,
    method              TEXT,
    origin              TEXT,
    authorization_mode  TEXT,
    policy              TEXT,
    logical_size        INTEGER,
    before_physical     INTEGER,
    after_physical      INTEGER,
    est_savings         INTEGER,
    actual_savings      INTEGER,
    verification        TEXT,
    recovery            TEXT,
    outcome             TEXT NOT NULL,
    error               TEXT
);
CREATE INDEX ix_hist_at ON optimization_history(at_utc);

CREATE TABLE recovery_records (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_uuid          TEXT NOT NULL,
    item_id                 INTEGER,
    root_path               TEXT,
    original_path           TEXT NOT NULL,
    file_key                TEXT,
    kind                    TEXT NOT NULL,
    status                  TEXT NOT NULL,
    archive_path            TEXT,
    archive_format          TEXT,
    entry_name              TEXT,
    archive_size            INTEGER,
    retained_original_path  TEXT,
    retained_size           INTEGER,
    sha256                  TEXT,
    size                    INTEGER,
    modified_ms             INTEGER,
    manifest_path           TEXT,
    created_utc             TEXT NOT NULL,
    retain_until_utc        TEXT,
    last_verified_utc       TEXT,
    note                    TEXT
);
CREATE INDEX ix_recovery_status ON recovery_records(status);
