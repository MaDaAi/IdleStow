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
4. IdleStow collects no data. Its optional in-app update check (off unless you turn it on) can show a newer
   release, but it does not know about advisories. The advisory and release notes are how users learn
   about a problem, so publish both.

## Scope

In scope: anything that could make IdleStow modify, delete, overwrite or expose data outside what the user
authorized; path traversal / link / junction escapes; archive extraction issues (ZIP-slip, bombs);
IPC message abuse; unsafe subprocess use; SQL injection; recovery failures that lose the only copy of data.

## Design

See [docs/architecture/safety-model.md](docs/architecture/safety-model.md) and
[docs/security/threat-model.md](docs/security/threat-model.md).

IdleStow needs no administrator rights and has no accounts. It opens no network ports for its own use. Its only
network use is the optional update check described below. That check never sends file names, file contents or
statistics.

## Updates

The in-app updater is the only part of IdleStow that uses the internet. It is opt-in: IdleStow asks on first
run, and closing that question counts as no.

* **Update check:** at most one request a day to `api.github.com`, asking for the latest release. Like any web
  request, it also reveals your IP address and a generic program name with the version.
* **Download:** only when you choose *Install update*. IdleStow downloads the release zip and `SHA256SUMS.txt`
  from the official GitHub release. The zip's SHA-256 must match the checksum file, or the download is discarded
  and nothing changes.
* **Transport:** HTTPS to GitHub hosts only. IdleStow refuses other hosts.
* **Install:** IdleStow unpacks the verified zip next to the app, checks that it contains the expected files and
  that the new jar reports the expected version, and then swaps the folders. Your data folder and your files are
  not touched.

**What this does not protect against.** The release is not code-signed. The checksum file is published with
the same release as the zip, so the checks protect against corrupted or tampered downloads in transit. They do
not protect against someone who takes over the developer's GitHub account and publishes a new release with a
matching checksum file. If you are unsure about a release, download it yourself from the GitHub releases page
and compare its SHA-256 with the published checksum before you run it.

**Verification status.** The updater code has unit tests. A full install on a real portable copy is not yet
recorded in [TEST_RESULTS.md](docs/development/TEST_RESULTS.md), so treat automatic installation as unverified
until it is.
