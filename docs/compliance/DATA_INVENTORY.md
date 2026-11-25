# Data inventory

Everything below stays on the user's computer. Nothing is sent to the developer or any third party.

| Data | Where | Personal? | Purpose | Retention / deletion |
|---|---|---|---|---|
| Paths, names, sizes, timestamps, attributes of files in authorized folders | `%LOCALAPPDATA%\IdleStow\idlestow.db` | May reveal personal information through file names | Find idle files, explain decisions | Until the folder is removed, the index is cleared, or the data folder is deleted |
| Activity evidence and classifications | same database | Indirectly | Recommendations | As above |
| Settings, authorized folders, autonomy policy digest | same database | No | Configuration | As above |
| Optimization history, recovery records, SHA-256 of changed files | same database | Indirectly (paths) | Undo and audit trail | Kept so files can be restored; deleted with the data folder |
| Recovery manifests (JSON) | data folder | Paths | Recovery without the database | As above |
| Retained originals | `<monitored folder>\.idlestow\retained\` | The user's own files | Undo of archive replacement | Until the user chooses *Reclaim space* in Recovery; never removed automatically |
| UI state, including Terms acceptance version and time | data folder `ui-state.json` | No | Window layout, Simple/Advanced choice, terms record | Deleted with the data folder |
| Update preference, last check time, skipped version | data folder `ui-state.json` | No | The opt-in update check | Deleted with the data folder |
| Update scratch files: download in progress, swap script and its log, database copy made before an update | data folder `updates` | Paths | Installing an update safely and rolling back | Download removed after unpacking; the rest stays until the data folder is deleted |
| Diagnostic logs | data folder `logs` | Paths (never contents) | Troubleshooting | Size-limited rotation; *Clear logs* |

Checked against the source: database migrations, `UiPrefs`, `Legal`, `OperationExecutor`, logback configuration.
