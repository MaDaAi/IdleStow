# ADR 0001: Modular monolith with a persistent Python worker

Status: Accepted (2026-11-24)

## Context

IdleStow needs Java as the primary language (UI, orchestration, persistence, OS integration) and Python for compression analysis, archives and verification.

## Decision

A single Java process (modules `core`, `platform`, `app`) supervises one persistent Python process that speaks line-delimited JSON over stdio (protocol v1). No microservices, no sockets.

## Consequences

Simple deployment and debugging; one place for authorization. Cost: a process boundary for hashing/archiving (measured overhead is small relative to I/O). The worker must be bundled (embeddable CPython) for distribution.
