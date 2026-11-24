# Security policy

IdleStow modifies files on users' disks, so we treat security and data-integrity issues as the highest priority.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private vulnerability reporting
(*Security → Report a vulnerability*) on the repository. Include:

* affected version and platform/filesystem,
* a description and minimal reproduction using synthetic files,
* the impact (data loss? authorization bypass? path escape?).

We aim to acknowledge reports within 7 days.

## Scope

In scope: anything that could make IdleStow modify, delete, overwrite or expose data outside what the user
authorized; path traversal / link / junction escapes; archive extraction issues (ZIP-slip, bombs);
IPC message abuse; unsafe subprocess use; SQL injection; recovery failures that lose the only copy of data.

## Design

See [docs/architecture/safety-model.md](docs/architecture/safety-model.md) and
[docs/security/threat-model.md](docs/security/threat-model.md).

IdleStow runs entirely locally, needs no administrator rights, opens no network ports and sends no data
anywhere.
