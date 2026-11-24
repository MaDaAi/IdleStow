# ADR 0007: Portable app image first; MSI only when WiX is available

Status: Accepted (2026-11-24)

## Context

jpackage needs the WiX Toolset to build MSI/EXE installers; it is not installed on the build machine and installing system-wide tools was out of scope.

## Decision

`scripts/package-windows.ps1` always builds the portable app image and zip, and builds an MSI only when WiX is on PATH. The CI packaging workflow does the same.

## Consequences

The release currently ships as a portable zip. Uninstalling = deleting the folder; user data in `%LOCALAPPDATA%\IdleStow` and archives/retained originals are untouched.
