# ADR 0004: Explicit journaled state machine instead of distributed transactions

Status: Accepted (2026-11-24)

## Context

A filesystem change and a database commit cannot be made atomic together.

## Decision

Journal each step durably before the filesystem change; make filesystem changes atomic renames where possible; on startup reconcile journal and disk (complete, roll back, or flag for review). Originals replaced by archives are moved to a retention store, never deleted by the optimization itself.

## Consequences

Some interrupted operations end in a REVIEW state that needs a user decision; this is deliberate (fail safe).
