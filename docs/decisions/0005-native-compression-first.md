# ADR 0005: Prefer transparent native compression; archive replacement is opt-in

Status: Accepted (2026-11-24)

## Context

Archive replacement changes how a file is opened; native NTFS compression keeps the path and normal behaviour.

## Decision

Recommendations and autonomy use NTFS compression (FSCTL_SET_COMPRESSION on the verified handle). Archive replacement requires a setting plus explicit per-plan confirmation, is never autonomous, and retains originals for a configurable period.

## Consequences

On filesystems without native compression, IdleStow indexes and recommends only when archive replacement is enabled; otherwise it explains why nothing is offered.
