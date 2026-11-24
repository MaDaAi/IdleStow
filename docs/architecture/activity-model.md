# Activity model

IdleStow never treats an old modification date as proof that a file is unused. Classification is
deterministic and explainable (`core/activity/ActivityClassifier.java`); every result carries the
evidence lines shown in the UI.

## Evidence

| Evidence | Source | Trust |
|---|---|---|
| Modification time | filesystem | always counts as activity |
| Creation time | filesystem (a copy or download resets it) | always counts as activity |
| Content/size change seen by the indexer | `activity_evidence: MODIFIED_OBSERVED` | activity |
| Opened through IdleStow | `OPENED_VIA_IDLESTOW` | activity |
| Last-access time | filesystem, **only when the volume reliably updates it** | activity / absence of activity |
| User declared active | `USER_DECLARED_ACTIVE` | activity |
| User confirmed inactive | `USER_CONFIRMED_INACTIVE` | inactivity |
| Folder designated archival | monitored-folder flag or policy | permission to rely on modification time |
| Reads by IdleStow itself (hashing, sampling, verification) | `SELF_READ` windows | **ignored** — a last-access update inside such a window is not user activity |

Last-access reliability per volume (`VolumeInfo.AccessTracking`):

* **Windows/NTFS** — read from `HKLM\SYSTEM\CurrentControlSet\Control\FileSystem\NtfsDisableLastAccessUpdate`
  (no admin rights needed). Updates are lazy (≈1 hour), which is irrelevant for day-based thresholds.
* **Linux** — mount options: `noatime` → disabled, `relatime` (default) → coarse (day granularity), `strictatime` → enabled.
* **macOS / other** — unknown.

## Rules (in order)

1. Pinned → **Pinned**. Protected / excluded / outside authorization → **Excluded**.
2. Any timestamp more than one day in the future → **Unknown** (no automatic action).
3. User declared active → **Active**.
4. Most recent activity evidence younger than the threshold → **Active** (within min(7, T/4) days) or **Probably active**.
5. Otherwise:
   * user confirmed inactive → **Inactive (high)**;
   * reliable last-access evidence → **Inactive (high)** once the observation period has passed, else **Potentially inactive (medium)**;
   * archival folder → same as above, relying on the user's designation;
   * otherwise → **Unknown (low)**: "last-access tracking is unavailable … automatic optimization is disabled".

Recommendations require *Inactive* or *Potentially inactive* with at least the configured confidence
(default medium). **Autonomous** optimization requires *Inactive* with at least the autonomous
confidence (default high, never below medium), a folder with autonomy enabled, and a confirmed policy.

## Observation period

Newly indexed files start an observation period (default 14 days). During it, a file can be
recommended (medium confidence) but never optimized autonomously.
