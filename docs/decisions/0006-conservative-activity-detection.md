# ADR 0006: Evidence-based, conservative activity detection

Status: Accepted (2026-11-24)

## Context

Modification time alone does not reveal reads; last-access times may be disabled or coarse; IdleStow's own reads update last-access times.

## Decision

Deterministic rules with explicit evidence; unknown when last-access tracking is unreliable unless the folder is designated archival or the user confirms; an observation period for new files; IdleStow's own reads are recorded and ignored.

## Consequences

Fewer automatic optimizations on volumes without access tracking, in exchange for never compressing files that are in use based on a guess.
