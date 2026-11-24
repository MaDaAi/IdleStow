package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.model.Enums.JobKind;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.Origin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Persistence for jobs, their per-file operations and the durable operation journal. */
public final class JobRepository {
    private final Database db;
    private final Clock clock;

    public JobRepository(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public record Job(long id, Long planId, JobKind kind, Method method, Origin origin, JobState state,
                      String createdUtc, String startedUtc, String finishedUtc, int totalItems, int doneItems,
                      int failedItems, long totalBytes, long doneBytes, String message, boolean cancelRequested,
                      boolean pauseRequested) { }

    public record Operation(long id, String uuid, long jobId, Long itemId, String path, String rootPath, String action,
                            Method method, String state, Integer policyVersion, String snapshot, String beforeState,
                            String afterState, String verification, String recovery, String startedUtc,
                            String finishedUtc, String error) { }

    public record JournalEntry(long id, long operationId, String step, String atUtc, String data) { }

    static Job mapJob(ResultSet rs) throws SQLException {
        String m = rs.getString("method");
        return new Job(rs.getLong("id"), Sql.getLong(rs, "plan_id"), JobKind.valueOf(rs.getString("kind")),
                m == null ? null : Method.valueOf(m), Origin.valueOf(rs.getString("origin")),
                JobState.valueOf(rs.getString("state")), rs.getString("created_utc"), rs.getString("started_utc"),
                rs.getString("finished_utc"), rs.getInt("total_items"), rs.getInt("done_items"),
                rs.getInt("failed_items"), rs.getLong("total_bytes"), rs.getLong("done_bytes"), rs.getString("message"),
                rs.getInt("cancel_requested") == 1, rs.getInt("pause_requested") == 1);
    }

    static Operation mapOp(ResultSet rs) throws SQLException {
        String m = rs.getString("method");
        return new Operation(rs.getLong("id"), rs.getString("uuid"), rs.getLong("job_id"), Sql.getLong(rs, "item_id"),
                rs.getString("path"), rs.getString("root_path"), rs.getString("action"),
                m == null ? null : Method.valueOf(m), rs.getString("state"),
                (Integer) rs.getObject("policy_version"), rs.getString("snapshot"), rs.getString("before_state"),
                rs.getString("after_state"), rs.getString("verification"), rs.getString("recovery"),
                rs.getString("started_utc"), rs.getString("finished_utc"), rs.getString("error"));
    }

    private String now() {
        return clock.instant().toString();
    }

    public long createJob(JobKind kind, Method method, Origin origin, Long planId, int totalItems, long totalBytes)
            throws SQLException {
        return db.txResult(c -> Sql.insert(c, "INSERT INTO jobs(plan_id, kind, method, origin, state, created_utc,"
                        + " total_items, total_bytes) VALUES(?,?,?,?,?,?,?,?)", planId, kind, method, origin,
                JobState.QUEUED, now(), totalItems, totalBytes));
    }

    public void setState(long jobId, JobState state, String message) throws SQLException {
        String n = now();
        db.tx(c -> Sql.update(c, "UPDATE jobs SET state=?, message=COALESCE(?, message),"
                        + " started_utc=CASE WHEN started_utc IS NULL AND ? IN ('PREFLIGHT','RUNNING') THEN ? ELSE started_utc END,"
                        + " finished_utc=CASE WHEN ? IN ('COMPLETED','CANCELLED','FAILED','RESTORED','RECOVERY_REQUIRED')"
                        + " THEN ? ELSE finished_utc END WHERE id=?",
                state, message, state.name(), n, state.name(), n, jobId));
    }

    public void progress(long jobId, int done, int failed, long doneBytes) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE jobs SET done_items=?, failed_items=?, done_bytes=? WHERE id=?",
                done, failed, doneBytes, jobId));
    }

    public void requestCancel(long jobId) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE jobs SET cancel_requested=1 WHERE id=?", jobId));
    }

    public void requestPause(long jobId, boolean pause) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE jobs SET pause_requested=? WHERE id=?", pause, jobId));
    }

    public Optional<Job> job(long id) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM jobs WHERE id=?", JobRepository::mapJob, id));
    }

    public List<Job> jobs(int limit) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM jobs ORDER BY id DESC LIMIT ?", JobRepository::mapJob, limit));
    }

    public List<Job> jobsInStates(List<JobState> states) throws SQLException {
        String in = String.join(",", states.stream().map(s -> "'" + s.name() + "'").toList());
        return db.read(c -> Sql.list(c, "SELECT * FROM jobs WHERE state IN (" + in + ") ORDER BY id",
                JobRepository::mapJob));
    }

    public long createOperation(long jobId, String uuid, Long itemId, String path, String rootPath, String action,
                                Method method, int policyVersion, String snapshot) throws SQLException {
        return db.txResult(c -> Sql.insert(c, "INSERT INTO job_operations(uuid, job_id, item_id, path, root_path,"
                        + " action, method, state, policy_version, snapshot, started_utc) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                uuid, jobId, itemId, path, rootPath, action, method, "PREFLIGHT", policyVersion, snapshot, now()));
    }

    /** Appends a journal step and updates the operation state in the same transaction. */
    public void journal(long opId, String step, String data) throws SQLException {
        String n = now();
        db.tx(c -> {
            Sql.update(c, "INSERT INTO operation_journal(operation_id, step, at_utc, data) VALUES(?,?,?,?)",
                    opId, step, n, data);
            Sql.update(c, "UPDATE job_operations SET state=? WHERE id=?", step, opId);
        });
    }

    public void finishOperation(long opId, String state, String before, String after, String verification,
                                String recovery, String error) throws SQLException {
        String n = now();
        db.tx(c -> {
            Sql.update(c, "INSERT INTO operation_journal(operation_id, step, at_utc, data) VALUES(?,?,?,?)",
                    opId, state, n, error);
            Sql.update(c, "UPDATE job_operations SET state=?, before_state=COALESCE(?,before_state),"
                            + " after_state=COALESCE(?,after_state), verification=COALESCE(?,verification),"
                            + " recovery=COALESCE(?,recovery), error=?, finished_utc=? WHERE id=?",
                    state, before, after, verification, recovery, error, n, opId);
        });
    }

    public void setBefore(long opId, String before) throws SQLException {
        db.tx(c -> Sql.update(c, "UPDATE job_operations SET before_state=? WHERE id=?", before, opId));
    }

    public List<Operation> operations(long jobId) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM job_operations WHERE job_id=? ORDER BY id",
                JobRepository::mapOp, jobId));
    }

    public Optional<Operation> operation(long opId) throws SQLException {
        return db.read(c -> Sql.one(c, "SELECT * FROM job_operations WHERE id=?", JobRepository::mapOp, opId));
    }

    /** Operations that never reached a terminal state (crash/interruption candidates). */
    public List<Operation> unfinishedOperations() throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM job_operations WHERE state NOT IN"
                + " ('DONE','FAILED','ROLLED_BACK','CANCELLED','NO_BENEFIT','REVIEW') ORDER BY id", JobRepository::mapOp));
    }

    public List<JournalEntry> journalOf(long opId) throws SQLException {
        return db.read(c -> Sql.list(c, "SELECT * FROM operation_journal WHERE operation_id=? ORDER BY id",
                rs -> new JournalEntry(rs.getLong("id"), rs.getLong("operation_id"), rs.getString("step"),
                        rs.getString("at_utc"), rs.getString("data")), opId));
    }

    public long countActive() throws SQLException {
        return db.read(c -> Sql.scalarLong(c, "SELECT COUNT(*) FROM jobs WHERE state IN"
                + " ('QUEUED','PREFLIGHT','RUNNING','VERIFYING','COMMITTING','PAUSED')"));
    }
}
