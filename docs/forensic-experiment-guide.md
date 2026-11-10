# Forensic experiment guide

Audience: students running the DailySpend mobile-forensics exercise on an authorised lab machine or
Android emulator. Everything here is synthetic and opt-in; the app has no capability that a normal
expense tracker would lack.

## 1. Scope and authorisation

- Run only on a device or emulator you own, inside the course lab network.
- The "suspicious activity" is simulated: generated fixtures, a session UUID, a JSONL log and five
  HTTP calls to a lab server. No real user expense ever leaves the device, and the app cannot be
  asked to do so remotely.
- Treat the artefacts as teaching material for acquisition and interpretation practice, not as legal
  evidence. The app writes them into its own private storage and can delete or overwrite them, so
  they carry no integrity guarantee (section 7).

## 2. Prerequisites

| Item | Value |
| --- | --- |
| Device | Android emulator (API 26+; the reference build targets API 36) |
| App under test | `app/build/outputs/apk/debug/app-debug.apk`, application id `com.example.expensetracker.debug` |
| Build | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug` |
| Install | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Launch | `adb shell am start -n com.example.expensetracker.debug/com.example.expensetracker.MainActivity` |
| Optional server | Python Flask listener on the host at port 5000; the emulator reaches it at `http://10.0.2.2:5000/` |
| Logcat capture | `adb logcat -s DailySpendLab:V > session-logcat.txt` before you start (or `adb logcat -v time` for everything) |

Confirm the baseline state: Settings should read lab switch off, `Session closed`,
`Server status ... not checked yet`, and `No lab events recorded yet`.

## 3. Phase 1 - ordinary usage (the cover story)

1. Add three to five expenses through the FAB: an amount, a description, a category, a payment
   method, a date. Note the exact wall-clock time you started.
2. Move the month selector, search the history list, filter by a category chip, edit one row, then
   delete another row through the confirmation dialog.
3. Acquire the database (section 5) and record the `expenses` rows.

Expected observations:
- `expenses` holds `amountMinor` integers, so `249.50` appears as `24950`; `createdAt` and
  `updatedAt` are epoch millis and differ once a row has been edited.
- A deleted row is gone from the table but its page may still be recoverable from the SQLite file
  until vacuumed - a good discussion point.
- `security_lab_events` is **empty** and the `files/lab_events/` directory does not exist yet,
  because nothing in the ordinary tracker writes there. This is the important negative control for
  the expense-triggered call-log sync in step 9 below: saving an expense with the switch off produces
  no artefact of any kind, so a `SYNTHETIC_CALLLOG_SYNC` row can only ever follow a deliberate
  opt-in.

## 4. Phase 2 - the controlled lab session

Run this sequence deliberately and time-stamp each step. The lab server may be offline; the point of
the exercise is that the app behaves identically either way.

1. Settings - turn on **Enable lab module**. Logcat: nothing fires; a `LAB_CONFIG_UPDATED` row is
   written the moment you toggle.
2. (Optional) set the *Lab server base URL* - `http://10.0.2.2:5000/` on an Android Studio emulator,
   `http://10.0.3.2:5000/` on Genymotion - and press **Save endpoint**. Try `ftp://10.0.2.2/` or
   `http://8.8.8.8/` and watch the client refuse them.
3. Press **Ping**. Settings shows `reachable` or `not reachable` with the reason and a UTC timestamp.
   If no server is listening you get `FAILED_NETWORK` behaviour with no crash.
4. Press **Start test session**. A random UUID appears as `open - <uuid>`, a `LAB_SESSION_START`
   row and a JSONL line appear, and the session start time is recorded.
5. Press **Generate synthetic fixtures**. `SYNTHETIC_EVENTS_GENERATED` is logged with five
   `Lab fixture - ...` records.
6. Press **Send synthetic batch to server**. You get `TRANSFER_REQUEST` (`PENDING`) followed by
   either `TRANSFER_RESPONSE` (`DELIVERED`) or `TRANSFER_FAILURE` (`FAILED_NETWORK`,
   `FAILED_TIMEOUT` or `FAILED_HTTP`).
7. Press **Run Security Test**. Exactly one fixed fixture - `syn-fixture-demo-001`, described as
   `Lab fixture - SYNTHETIC_TRANSACTION_VIEWED purpose=MOBILE_FORENSICS_TEST` - is posted to the same
   ingestion endpoint, and a `SECURITY_TEST_NOTIFICATION` row is written afterwards. On an emulator
   that allows it, the app also shows its own notification with the same text. On Android 13+ the
   card offers **Allow lab result notifications** first; run the test before *and* after granting it
   and diff the archived rows - the earlier one says `posted: false` while both record the same
   result text, which is a neat lesson in not trusting a UI claim over a log. Tap the button twice in
   quick succession: the second press is refused with `one is already running` and sends nothing.
   Discuss what the row proves when the server is a) a correct lab server, b) one whose routes do not
   match the contract (any 404 - see [server-integration.md](server-integration.md) §5 for what the
   lab-host server now answers), and c) not listening at all.
8. Leave the switch on, kill the Flask server, and repeat steps 4-6 to demonstrate Behaviour E. Then
   turn **Allow one retry** on, press the transfer button again, and confirm that a `TRANSFER_RETRY`
   row appears only on the second manual press.
9. Keep the switch on and **add a new expense from the dashboard FAB** - any ordinary amount and
   description. The save succeeds exactly as it does with the module off (there is no lab button,
   banner or status line on that screen), and afterwards the device posts the six call-log fixtures
   from `lab/SyntheticCallLogFactory.kt` that it has not already submitted: a `TRANSFER_REQUEST`
   pointing at `/api/lab/synthetic-call-logs`, then `SYNTHETIC_CALLLOG_SYNC` with
   `accepted`/`duplicates` from the server. Save a **second** expense and only a `TRANSFER_REQUEST`-
   free `SYNTHETIC_CALLLOG_SYNC` with `"fixtureCount":0` appears - the ids are remembered in
   `shared_prefs/daily_spend_lab.xml` under `lab_processed_fixture_ids`, which is the dedupe
   artefact. Edit or delete an expense instead and nothing at all is written.
   Now stop the Flask server and save a third expense: the row is `TRANSFER_FAILURE` with
   `kind: SYNTHETIC_CALLLOG` and `FAILED_NETWORK`, the ids stay unmarked, and restarting the server
   and saving once more shows the whole batch going out - the retention-and-retry artefact.
   Stress what these records are *not*: `+91-90000-000NN` is a reserved test block, the app holds no
   `READ_CALL_LOG`, `WRITE_CALL_LOG` or `READ_PHONE_STATE` permission, and nothing here reads
   Android's call-log provider. Push a hand-made record with a real-shaped number at the server
   (`docs/server-integration.md` §5 shows the curl) and it answers 400 with
   `NON_FIXTURE_CALLLOG_REJECTED` while storing nothing - the "someone tried to smuggle real history"
   artefact.
10. Press **Fetch allowlisted test command**. Queue an action on the server first
    (`POST /api/lab/queue {"command":"WRITE_TEST_MARKER"}`) and you get `LAB_COMMAND_RECEIVED` on the
    device. A reply that is not one of the four allowlisted names, or that carries parameters or no
    timestamp, gives `LAB_COMMAND_REJECTED` with the reason in the payload - the lab-host server
    refuses to send such names, so demonstrate that branch by editing the queue by hand or with the
    §4 skeleton.
11. Press **Stop test session**, then acquire again and compare with Phase 1.

Expected artefacts after Phase 2:

| Event type | Delivery status | What it proves |
| --- | --- | --- |
| `LAB_CONFIG_UPDATED` | `LOCAL_ONLY` | the switch was toggled and what the endpoint was set to |
| `LAB_SESSION_START` | `LOCAL_ONLY` | a session with a UUID started at a UTC instant, app version attached |
| `SYNTHETIC_EVENTS_GENERATED` | `LOCAL_ONLY` | synthetic fixtures were produced in memory, count attached |
| `TRANSFER_REQUEST` | `PENDING` | an outbound transfer was attempted, target and record count |
| `TRANSFER_RESPONSE` | `DELIVERED` | the server accepted it, HTTP status and response timestamp |
| `TRANSFER_FAILURE` | `FAILED_NETWORK` / `FAILED_TIMEOUT` / `FAILED_HTTP` / `INVALID_PAYLOAD` | the attempt failed and why |
| `TRANSFER_RETRY` | `PENDING` | the user explicitly enabled one retry |
| `LAB_COMMAND_RECEIVED` / `LAB_COMMAND_REJECTED` | `DELIVERED` / `REJECTED` | the allowlist gate and its verdict |
| `TEST_MARKER_WRITTEN` | `LOCAL_ONLY` | a file was written inside the app's lab directory |
| `SECURITY_TEST_NOTIFICATION` | `LOCAL_ONLY` | the text of the notification DailySpend posted about its own security test: title, message, channel, id, UTC time, HTTP status and whether the system accepted it. It is the app's own artefact - no other application's notifications are ever read |
| `SYNTHETIC_CALLLOG_SYNC` | `DELIVERED` / `LOCAL_ONLY` | the expense-triggered call-log fixture send: trigger (`EXPENSE_CREATED`), how many were new, how many were already submitted, the exact `sentRecordIds`, and the server's `accepted`/`duplicates`. A `fixtureCount: 0` row is the dedupe proof; a `LOCAL_ONLY` row is the "nothing to send" case |
| `LAB_SESSION_END` | `LOCAL_ONLY` | session closed, with the number of events recorded |

Behaviour map: A = session start with a random id; B = synthetic fixtures; C = transfer to a
configurable endpoint with status and response time recorded; D = allowlisted commands only;
E = the session still works offline and only retries when the user says so; F = JSONL records with
event id, session id, type, UTC timestamp, synthetic flag and request status; G = the call-log
fixtures are generated in code, deduplicated by persistent id, retained locally when the server is
down and re-offered on the next app interaction.

## 5. Acquisition

Debug builds keep the app debuggable, so `run-as` works on a rooted or emulator target:

```bash
# database (both tables live in one file)
adb exec-out run-as com.example.expensetracker.debug \
  cat databases/daily_spend.db > daily_spend.db

# lab settings
adb exec-out run-as com.example.expensetracker.debug \
  cat shared_prefs/daily_spend_lab.xml > daily_spend_lab.xml

# JSONL forensic log, including the rotation file if present
adb exec-out run-as com.example.expensetracker.debug \
  cat files/lab_events/lab_forensic_events.jsonl > lab_forensic_events.jsonl

# directory listing for the exhibit note
adb shell run-as com.example.expensetracker.debug ls -l databases files files/lab_events shared_prefs

# marker file written by WRITE_TEST_MARKER
adb exec-out run-as com.example.expensetracker.debug \
  cat files/lab_events/lab_test_marker.txt
```

If `run-as` is unavailable (a release-signed build on a non-rooted device), use a device image and
an SQLite parser instead, or run the emulator with a root shell:

```bash
adb root
adb shell ls -l /data/data/com.example.expensetracker.debug/
adb backup -f daily_spend.ab com.example.expensetracker.debug   # the backup rules list only the database
```

Read the acquired file:

```bash
sqlite3 daily_spend.db ".tables"
sqlite3 daily_spend.db "SELECT id, amountMinor, category, description, paymentMethod, \
  datetime(expenseDate/1000,'unixepoch') FROM expenses ORDER BY expenseDate DESC;"
sqlite3 daily_spend.db "SELECT sessionId, eventType, deliveryStatus, \
  datetime(eventTimestamp/1000,'unixepoch') FROM security_lab_events ORDER BY id;"
sqlite3 daily_spend.db "SELECT payloadJson FROM security_lab_events WHERE eventType='TRANSFER_REQUEST';"
sqlite3 daily_spend.db "SELECT eventType, deliveryStatus, payloadJson FROM security_lab_events \
  WHERE eventType='SYNTHETIC_CALLLOG_SYNC' OR (eventType='TRANSFER_FAILURE' AND payloadJson LIKE '%CALLLOG%');"
```

The dedupe set is a plain preference, so it is worth pulling separately - it is what lets you say
*which* fixtures had already gone out before this acquisition:

```bash
adb exec-out run-as com.example.expensetracker.debug \
  cat shared_prefs/daily_spend_lab.xml | grep lab_processed_fixture_ids
```

On the server side, the matching exhibit is its own SQLite table (the file named by `LAB_DB`, by
default `dailyspend_lab.sqlite3` next to `app.py`):

```bash
sqlite3 dailyspend_lab.sqlite3 ".tables"
sqlite3 dailyspend_lab.sqlite3 "SELECT record_id, caller_number, callee_number, direction, \
  call_type, duration_seconds, trigger, received_at_utc FROM synthetic_call_logs ORDER BY record_id;"
sqlite3 dailyspend_lab.sqlite3 "SELECT event_type, details, at_utc FROM events \
  WHERE event_type LIKE '%CALLLOG%' ORDER BY id;"
curl -s http://127.0.0.1:5000/api/lab/synthetic-call-logs | python -m json.tool
```

`record_id` is unique in that table, which is why a replayed batch reports duplicates rather than
adding rows. Every value there is a fixture: `SELECT count(*) FROM synthetic_call_logs WHERE
caller_number NOT LIKE '+91-90000-%'` returns 0, and it stays 0 because a non-fixture record is
refused with HTTP 400 before it can be written.

### The device's own call-log provider

The app's fixtures are not the only call data in the sandbox. The emulator's system call log holds
genuine provider records, and the course acquires them examiner-side - the analyst reads the
provider database, the app never does. Full procedure, evidence layout and per-run hashes are in
[device-data-acquisition.md](device-data-acquisition.md); the short form:

```bash
adb root
adb shell ls -l /data/data/com.android.providers.contacts/databases/
adb shell content query --uri content://call_log/calls --projection date | grep -c Row
adb pull /data/data/com.android.providers.contacts/databases/calllog.db <evidence-dir>/
adb pull /data/data/com.android.providers.contacts/databases/calllog.db-journal <evidence-dir>/
sqlite3 <evidence-dir>/calllog.db "PRAGMA integrity_check; SELECT _id, date, number, duration, type, source_package FROM calls ORDER BY date DESC;"
```

Acquire this *after* the lab session, and keep it in a separate exhibit from the app's own database:
the two have different provenance, and conflating them is the error this exercise is built to catch.

## 6. Analysis exercise for students

1. Reconstruct a timeline that merges three sources: logcat lines, `security_lab_events` rows and
   JSONL lines. Where do they disagree, and why (clock source, truncation, rotation, buffering)?
2. Separate the synthetic call-log fixtures from real call history, the way you would have to in a
   live case. Point at: the reserved `+91-90000-` number block, the `syn-call-` id namespace, the
   `source: SYNTHETIC_LAB` and `synthetic: true` fields, the absence of any `READ_CALL_LOG` /
   `WRITE_CALL_LOG` / `READ_PHONE_STATE` declaration in the manifest (`aapt2 dump xmltree --file
   AndroidManifest.xml app-debug.apk`), and the fact that the timestamps are all on one fixed day
   (`2026-10-12`) regardless of when the run happened. Then argue the other way: if these rows
   *were* real, what would the `SYNTHETIC_CALLLOG_SYNC` + `TRANSFER_REQUEST` pair and the server's
   `synthetic_call_logs` table tell you about what left the device, when, and how many times?
3. Determine whether any real user expense left the device. Show the evidence: `expenses` rows
   versus payload descriptions (`Lab fixture - ` prefix, `syn-` ids) and the ingestion payload in the
   `TRANSFER_REQUEST` row.
4. Establish whether the lab module was ever enabled, when, and by what action; correlate
   `daily_spend_lab.xml` (`lab_enabled`) with the earliest `LAB_CONFIG_UPDATED` row. Then use
   `lab_processed_fixture_ids` in the same file to say which call-log fixtures had already been
   submitted at the moment of acquisition, and check that against the `sentRecordIds` arrays.
5. Identify the endpoint the app was configured to reach and whether it responded; distinguish
   `FAILED_NETWORK`, `FAILED_TIMEOUT` and `FAILED_HTTP` in the log.
6. Show that the app cannot be commanded arbitrarily: find a `LAB_COMMAND_REJECTED` row if you
   returned a hostile command from the server (`rm -rf /`, `READ_SMS`, `INSTALL_APK`) and explain
   which validation rule caught it.
7. Note what is *missing* by design: no SMS or contact artefacts, no call history **acquired by the
   app itself** (its call-log rows are code-generated fixtures - see item 2 - and the provider
   database acquired examiner-side in section 5 is evidence about the device, not about the app),
   no location, camera or microphone capture, no scheduled work (`adb shell dumpsys jobscheduler | grep expensetracker`,
   `adb shell dumpsys alarm` and `adb shell dumpsys package com.example.expensetracker.debug` all
   show nothing beyond the launcher activity), no receiver, no hidden launcher icon, no additional
   APK.
8. Discuss artefact volatility: which of these survive an app update, which survive "clear storage",
   and which survive a device backup.
9. Attribute the provider database you acquired in section 5. For each row decide whether it is an
   examiner-seeded reference record, a fixture the app transmitted, or genuine device traffic - and
   name the artefact that settles it. `source_package` is NULL on every row, so it cannot attribute
   anything; the number block, the timestamp clustering and the manifest's permission list can. Then
   answer the question a defence counsel would ask: what would change your conclusion if the app
   *had* held `READ_CALL_LOG`?

## 7. Integrity limits to state in a report

- The JSONL log lives in app-private storage; the app can rewrite or delete it (`Clear lab events and
  log`), and a rooted device can edit it freely. It is not append-only and not signed.
- The log is bounded at 500 records and 256 KB with a single rotation file, so a long session loses
  the oldest entries. `readAll()` also silently skips lines that fail to parse, so a partially
  written final line disappears from the parsed view while still being present as bytes - check the
  raw file before claiming record counts.
- `security_lab_events` has a `clearAll()` path, and Room rows carry no signature.
- Timestamps come from device wall-clock time; a shifted emulator clock shifts all artefacts
  equally. Only the `LAB_SESSION_START` payload ties a session to a start instant.
- Logcat is ephemeral and buffered: capture it during the run, do not claim it afterwards.
- Amounts in `payloadJson` are minor units; `12500` means `125.00` INR, and that is fixture data,
  not the user's spending.

## 8. Cleanup

```bash
adb shell pm clear com.example.expensetracker.debug   # removes database, prefs and lab files
adb uninstall com.example.expensetracker.debug
```

Or from the app: Settings - University security lab - **Clear lab events and log** (this removes the
lab table rows, the JSONL files and the `lab_processed_fixture_ids` set, and leaves the user's
expenses untouched). Resetting that set is what makes the demonstration repeatable on the same
install: without it the next expense save reports `fixtureCount: 0`, because the six fixtures have
already been submitted.
