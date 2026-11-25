# Security policy

IdleStow modifies files on users' disks, so we treat security and data-integrity issues as the highest priority.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private vulnerability reporting
(*Security → Report a vulnerability*) on the repository. Include:

* affected version and platform/filesystem,
* a description and minimal reproduction using synthetic files,
* the impact (data loss? authorization bypass? path escape?).

The maintainer tries to acknowledge reports within 7 days. This is a volunteer project, so there is no
guaranteed response time.

## If a data-loss or security bug is found

1. Confirm it with synthetic files only and record the affected versions.
2. Publish a GitHub security advisory (or an issue for non-sensitive data-loss bugs) that tells users how to
   stop it (pause IdleStow or start it with `--read-only`) and how to restore files (see
   [docs/user-guide/recovery.md](docs/user-guide/recovery.md)).
3. Fix it with a regression test, release a new version, and update the advisory.
4. IdleStow has no automatic updates and collects no data, so the advisory and release notes are the only
   way to reach users.

## Scope

In scope: anything that could make IdleStow modify, delete, overwrite or expose data outside what the user
authorized; path traversal / link / junction escapes; archive extraction issues (ZIP-slip, bombs);
IPC message abuse; unsafe subprocess use; SQL injection; recovery failures that lose the only copy of data.

## Design

See [docs/architecture/safety-model.md](docs/architecture/safety-model.md) and
[docs/security/threat-model.md](docs/security/threat-model.md).

IdleStow runs entirely locally, needs no administrator rights, opens no network ports and sends no data
anywhere.
