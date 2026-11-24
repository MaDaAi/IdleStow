# ADR 0002: Target Java 21 LTS

Status: Accepted (2026-11-24)

## Context

The specification prefers Java 25 LTS. The development machine has JDK 18, 19, 21 and 24 installed but no JDK 25; installing system-wide software was out of scope.

## Decision

Compile with `--release 21` and bundle a Java 21 runtime via jpackage/jlink. JavaFX 21 LTS is used to match.

## Consequences

Works on every current LTS JDK ≥ 21. Upgrading to 25 later is a one-line change (`maven.compiler.release`) plus re-testing.
