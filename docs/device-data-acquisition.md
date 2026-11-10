# Examiner-side acquisition of actual device call history

Added 2026-10-21 in response to a course requirement change: the demo now has to recover **actual**
call-log content from the lab device, not only the synthetic fixtures the app generates. This
document records how that requirement is met, and where its boundary sits.

## 1. The requirement, and the shape of the answer

| Requirement | How it is satisfied |
| --- | --- |
| Recover the device's real call history for analysis | The examiner acquires the Android call-log provider database directly from the running emulator over `adb`, as root, and preserves it as a hashed evidence set |
| Run inside a controlled sandbox | The target is a course AVD (`Medium_Phone`, API 26) with no SIM, no signed-in accounts and no personal data. The only call history on it is data the exercise itself put there |
| Ask for the required permission | Asked of the **operator's host**, not of the phone: `adb root` on a device the course owns. No Android runtime-permission prompt is involved, because nothing on the device is being asked to consent to a third-party read |
| Keep the app's own posture intact | Unchanged. DailySpend still declares `INTERNET` and `POST_NOTIFICATIONS` only, still holds no `READ_CALL_LOG`/`WRITE_CALL_LOG`/`READ_PHONE_STATE`, and no code path in it queries or writes `CallLog.Calls` |

The distinction in row three is the whole design decision. A permission prompt on the device
authorises *an application* to read the call log, and that authorisation travels with the APK
wherever it is installed. Acquisition by the examiner authorises *this analyst, on this lab device,
at this time*, and expires with the session. The second is what a forensics course teaches; the
first is what a surveillance tool needs.

## 2. Procedure

Read-only against the device. Nothing in this section writes to or deletes anything on it.

```bash
# 0. Confirm the analyst is root on the lab emulator and locate the provider database.
adb shell id -u
adb shell ls -l /data/data/com.android.providers.contacts/databases/

# 1. Cross-check the row count through the content provider before pulling the file.
adb shell content query --uri content://call_log/calls --projection date | grep -c Row

# 2. Acquire the database and its journal, then hash both.
adb pull /data/data/com.android.providers.contacts/databases/calllog.db       <evidence-dir>/
adb pull /data/data/com.android.providers.contacts/databases/calllog.db-journal <evidence-dir>/
sha256sum <evidence-dir>/calllog.db

# 3. Work on the copy, never on the original.
sqlite3 <evidence-dir>/calllog.db "PRAGMA integrity_check;"
sqlite3 <evidence-dir>/calllog.db "SELECT _id, date, number, duration, type, source_package \
  FROM calls ORDER BY date DESC;"
```

Pull the `-journal` (or `-wal`) alongside the database: an acquisition of the main file alone can
miss the most recent committed transactions, and an empty journal is itself a finding worth
recording rather than a file to skip.

## 3. Evidence set produced by this run

Directory: `C:\DailySpendServer\evidence\emulator_calllog_20261021T023356Z\`

| File | Contents |
| --- | --- |
| `calllog.db` | Acquired provider database, 45,056 bytes, SHA-256 `220b86e813e8a075ce3a4170675e23aedda4186324b3804a9523ff965410fc2a`, `PRAGMA integrity_check` = `ok` |
| `calllog.db-journal` | Acquired journal, 0 bytes (empty at acquisition time), SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `calllog_export.csv` | 25 rows, one per call record, with `_id`, number, contact name, formatted number, duration, decoded call type, presentation flag, read flag, country ISO code, `source_package`, UTC timestamp and raw epoch milliseconds |
| `calllog_export.json` | The same 25 rows in JSON, for scripting |
| `acquisition_manifest.json` | Acquisition time, device, AVD, source path, method statement, per-file hashes and sizes, row counts, type distribution, `sqlite_sequence` values, integrity result |

Acquired at `2026-10-21T02:33:56Z` from `emulator-5554`. Type distribution: 10 INCOMING, 7 OUTGOING,
6 MISSED, 2 REJECTED. `source_package` is NULL on all 25 rows.

Two findings the students should have to explain:

- **24 of 25 rows** carry numbers inside the reserved `+91-90000-` test block and fall in
  `2026-10-14 07:34` to `2026-10-17 04:34` UTC. Those were written examiner-side on 2026-10-17 as
  reference data (see `C:\DailySpendServer\archives\emulator_calllog_seed_20261017T0525Z.txt`).
- **Row `_id 26`** is not in that block: `6505551212`, formatted `(650) 555-1212`, MISSED,
  `2026-10-21T02:24:56Z`, country ISO `US`. Nothing in the exercise wrote it - it arrived from the
  emulator's own telephony layer about a minute after boot, and `sqlite_sequence` for `calls` being
  26 against 25 live rows is the deletion/insertion history that explains the gap.

## 4. What this acquisition does *not* authorise

- **No change to the app.** The enforcement table in
  [docs/security-assumptions-and-limitations.md](security-assumptions-and-limitations.md) §2 stands
  unchanged. Adding `READ_CALL_LOG` to DailySpend would convert the course APK into a working
  harvester, and the three-layer synthetic guard (`SyntheticCallLogFactory.isSynthetic()`,
  `LabCallLogPayloadBuilder.build()`, `OkHttpLabApiClient.ingestSyntheticCallLogs()`) exists
  precisely to make that path impossible rather than merely unused.
- **No upload of acquired data to the lab server.** The ingest contract requires
  `source: SYNTHETIC_LAB` and `synthetic: true`, and validates numbers against `+91-90000-\d{1,12}`.
  Posting device-acquired rows there would label genuine data as invented and would be rejected on
  the number format anyway. `synthetic_call_logs` holds fixtures and nothing else; that invariant is
  what makes the dashboard safe to project in front of a class.
- **No live-device scope.** Everything above was run against a course AVD. The same commands against
  a handset containing a real person's call history is a different activity with legal and ethical
  requirements this course does not cover.

If the demo needs the acquired rows visible in the browser, the bounded way to do it is a separate
table and a separate dashboard tab labelled by provenance (`DEVICE_ACQUIRED_ADB`), never merged into
the synthetic records. That is a server-side change and has not been built.

## 5. Verification status

- **Executed 2026-10-21:** the §2 procedure end to end against `emulator-5554`, including the
  content-provider cross-check (25 rows), both pulls, both hashes, `PRAGMA integrity_check`, the
  schema read that produced the export column list, and the row-26 finding.
- **Written, not executed:** the browser-side provenance tab described in §4 does not exist.
- **Not applicable:** no Android runtime permission prompt appears anywhere in this procedure, so
  there is nothing to verify on that axis.
