# DailySpend

A personal expense tracker for Android (Kotlin + Jetpack Compose + Room) that doubles as the
mobile client for an **authorised university digital-forensics lab**. The tracker itself is an
ordinary offline app: everything the user types stays in a local SQLite database on the device.
The security-lab module is a separate, opt-in, feature-flagged part of the app that simulates
"suspicious" activity with **synthetic fixtures only**, so students can acquire and interpret real
artefacts (a Room database, a SharedPreferences file, a JSONL event log, logcat lines, an outbound
HTTP request) without ever handling spyware.

This project is a forensic teaching aid, not a deployable surveillance tool. The prohibitions in
[docs/security-assumptions-and-limitations.md](docs/security-assumptions-and-limitations.md) are
enforced in code, not just in documentation.

## Contents

| Document | What is in it |
| --- | --- |
| This README | Feature overview, stack, build/install commands, test status |
| [docs/architecture.md](docs/architecture.md) | Mermaid diagrams, module breakdown, data flow |
| [docs/forensic-experiment-guide.md](docs/forensic-experiment-guide.md) | Runbook for the lab session, acquisition commands, expected artefacts |
| [docs/security-assumptions-and-limitations.md](docs/security-assumptions-and-limitations.md) | Threat model, prohibitions, cleartext HTTP risk, what the logs do *not* prove |
| [docs/server-integration.md](docs/server-integration.md) | Flask server contract (5 endpoints), allowlisted commands, request/response samples |
| [docs/sample-synthetic-events.md](docs/sample-synthetic-events.md) | Sample JSONL log lines, ingestion payloads, Room rows, call-log fixtures |
| [docs/device-data-acquisition.md](docs/device-data-acquisition.md) | Examiner-side acquisition of the device's real call history, evidence set, and what that path does not authorise |

## Screens

**Dashboard** (`ui/dashboard/DashboardScreen.kt`) - spending total for the selected month,
transaction count, categories used, all-time entries, spending-by-category bars with share
percentages, recent transactions, a month stepper (left/right icons), and a FAB labelled
"Add expense". A fresh install shows an explicit empty state instead of zeros.

**Add / edit expense** (`ui/expenses/ExpenseEditScreen.kt`) - amount (rupee prefix, minor units),
description, one of five categories, one of five payment methods, and a date picker with a "Today"
shortcut. Validation errors appear under the field they belong to and nothing is written to the
database until validation passes.

**Transaction history** (`ui/history/HistoryScreen.kt`) - every stored expense newest-first, a
description search box, category filter chips, tap-a-row to edit, and a delete icon that always
asks for confirmation first.

**Settings** (`ui/settings/SettingsScreen.kt`) - app version and build type, local database facts
(engine, file name, stored row count, oldest/newest entry), and the clearly labelled
**University security lab** card: the enable switch, the lab server base URL, the explicit retry
switch, session and server-status lines, forensic log size, and the manual buttons
(Start/Stop test session, Ping, **Run Security Test**, Generate synthetic fixtures, Send synthetic
batch to server, Fetch allowlisted test command, Clear lab events and log). *Run Security Test* posts
one fixed fixture and then announces its own result through a DailySpend notification plus a
`SECURITY_TEST_NOTIFICATION` artefact row; on Android 13+ the card first offers **Allow lab result
notifications**, which is the only place `POST_NOTIFICATIONS` is requested. Below it, the recorded lab
events.

**Security-lab dashboard** (`ui/lab/LabDashboardScreen.kt`, opened from the lab card's *Open lab
dashboard* button) - the control panel for the periodic synthetic call-log generator. It carries a
permanent "SYNTHETIC LAB DATA - every record on this screen is fabricated" banner and four regions:
the lab session card (state, session id, start time, Start/Stop session, Deliver now), the periodic
generation card (Stopped / Running / Waiting for delivery, cycles run, last cycle, next cycle with a
live countdown, Start/Stop generating), the cadence fields (batch size 1-20, interval 5-600 s, an
Apply button whose range errors print under the fields) and the live statistics row (generated,
delivered, pending, failed, retries) above the stored-record list, which is filterable by call type
and delivery status and labels every row `SYNTHETIC LAB DATA`, the reserved test numbers, the cycle id
and the delivery status. It is read-only with respect to real expenses and refuses to act while the
lab switch is off.

## Technology stack

| Concern | Choice | Version |
| --- | --- | --- |
| Language | Kotlin | 2.2.21 |
| Build | Android Gradle Plugin / Gradle wrapper | 8.13.2 / 8.14.6 |
| UI | Jetpack Compose BOM, Material 3 | 2026.01.01 |
| Architecture | ViewModel + StateFlow, hand-written DI container | lifecycle 2.9.4 |
| Persistence | Room (KSP) over SQLite, Flow queries | 2.7.2 |
| Network | OkHttp (Retrofit deliberately unused), kotlinx.serialization | 4.12.0 / 1.9.0 |
| SDK levels | minSdk 26, compileSdk/targetSdk 36 | - |
| Unit tests | JUnit 4, coroutines-test, MockWebServer | 4.13.2 / 1.10.2 / 4.12.0 |
| UI tests | `androidx.compose.ui:ui-test-junit4`, AndroidX Test | BOM-managed |

Money is stored as integer minor units (paise) in `amountMinor`, never as a floating point value.
Categories are the fixed set Food, Transport, Shopping, Education, Other; payment methods are Cash,
UPI, Debit Card, Credit Card, Net Banking.

## Requirements

- Android Studio current stable (this project was built with AGP 8.13.2, which needs a 2025-or-later
  Studio) with the Android SDK platform 36 and build-tools installed.
- JDK 17+. **Use the JDK bundled with Android Studio** - the system JDK on the reference machine is
  Java 27, which Gradle 8.14 rejects. On Windows:

  ```bash
  export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
  ```

  In PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.

## Build and install

From the project root (`gradlew` is the checked-in wrapper, so no local Gradle install is needed):

```bash
# 1. Debug APK -> app/build/outputs/apk/debug/app-debug.apk
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug

# 2. JVM unit tests (192 tests in the debug variant, no device needed)
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest

# 3. Instrumentation APK -> app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebugAndroidTest

# 4. Release APK (signed with the local debug key so it installs; lab endpoint compiled out,
#    cleartext HTTP disabled) -> app/build/outputs/apk/release/app-release.apk
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleRelease
```

Install and launch on a running emulator or device:

```bash
adb devices                                  # confirm the target is attached

# Debug build (application id com.example.expensetracker.debug)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.expensetracker.debug/com.example.expensetracker.MainActivity

# Release build (application id com.example.expensetracker) - signed with the debug key,
# so it installs on any emulator/device. Debug and release use different application ids and
# can coexist on the same device.
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.example.expensetracker/com.example.expensetracker.MainActivity
```

The release APK is signed with the **debug keystore** (`~/.android/debug.keystore`, generated on
demand by Gradle) purely so the course artifact installs without committing a private keystore or
its password. This is a lab convenience, not a production signing strategy - before publishing
anywhere, replace the `release` `signingConfig` in [app/build.gradle.kts](app/build.gradle.kts) with
a real keystore whose credentials come from an environment or secret store, never a tracked file.

Run the Compose UI tests once an emulator is up (see *Test status* below - they have **not** been
executed on the reference machine):

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew connectedDebugAndroidTest
```

### Talking to the lab server from the emulator

Both APKs are plain Android apps with no Play Services dependency, and they carry `x86` / `x86_64`
native stubs, so they install on any emulator or device running **Android 8.0 (API 26) or newer** -
Android Studio's emulator and Genymotion both work.

The host machine is reachable through a different address depending on the emulator:

| Emulator | Host address | Lab endpoint to type on Settings |
| --- | --- | --- |
| Android Studio (AVD) | `10.0.2.2` | `http://10.0.2.2:5000/` |
| Genymotion (VirtualBox/QEMU) | `10.0.3.2` (the guest default gateway) | `http://10.0.3.2:5000/` |

Debug builds permit cleartext HTTP only for the hosts listed by the build - `10.0.2.2`, `10.0.3.2`,
`localhost` and `127.0.0.1` ([app/build.gradle.kts](app/build.gradle.kts)); every other host must be
HTTPS, and `LabEndpointPolicy` refuses anything else in the *Lab server base URL* field. Release
builds compile the endpoint and the cleartext permission away entirely.

If your emulator's host address is not on that list, keep the allowlist untouched and tunnel instead:

```bash
adb reverse tcp:5000 tcp:5000     # guest localhost:5000 -> host :5000
# then use http://localhost:5000/ in the app
```

Run the Flask listener on `0.0.0.0` (not `127.0.0.1`) so the emulator's inbound connection is
accepted; a `127.0.0.1`-bound server only answers `adb reverse` traffic.

The server side of the lab lives outside this repository, in `C:\DailySpendServer\app.py`. Three
environment variables drive it: `LAB_PORT` (default 5000), `LAB_DB` (default
`dailyspend_lab.sqlite3` - give each student group a new file name, and never delete an old one, they
are the evidence) and `LAB_HOST`, which defaults to `0.0.0.0` because the emulator reaches the host
through NAT and a loopback bind cannot serve that; set `LAB_HOST=127.0.0.1` for a host-only demo or use
the `adb reverse` tunnel above. Alongside the five contract routes it now serves a read-only browser
dashboard at `http://127.0.0.1:5000/dashboard` plus three supporting read endpoints
(`/api/lab/dashboard/summary`, `/api/lab/sessions`, `/api/lab/synthetic-call-logs/<record_id>`); the
original ingest contract, JSON field names and response shapes are unchanged. The dashboard opens on a
plain record listing - synthetic banner, Refresh, the auto-refresh switch and the stored-records table.
That table has seven columns (caller, recipient, direction, call type, started, duration, received);
the event id, session id and generation trigger were dropped from it on 2026-10-17 and are still
available in the row's detail dialog and through the event-id search, and every row keeps its `SYNTH`
marker.
The analysis half (summary cards, the counts-by-type / -by-status / -by-trigger bars, the session table
and the filter row) sits behind a **Show analysis** checkbox that is off by default, and the two
analysis endpoints are not even requested until it is ticked; the per-record `SYNTH` chip and the
detail dialog's fixture-guard verdict only appear with the toggle on, and switching it off clears the
filters. `verify_call_logs.py`
(9 checks) and `verify_dashboard.py` (58 checks) both run against a throwaway `LAB_DB`. Because the
server runs with `debug=False`, Jinja caches `templates/dashboard.html` - restart the process after
editing it.

For Genymotion, enable SDK adb integration (Settings -> ADB -> *Use local adb from the Android SDK
directory*) so the virtual device appears in `adb devices` and `./gradlew connectedDebugAndroidTest`
can target it.

## Test status (as of this build)

| Check | Command | Result |
| --- | --- | --- |
| JVM unit tests | `./gradlew testDebugUnitTest testReleaseUnitTest` | **Debug re-run 2026-10-17 with `--rerun-tasks`** - 192 tests, 0 failures, 0 skipped across 17 classes. The release variant last passed on 2026-10-15 (165 tests then) and has **not** been re-run since the periodic generator landed |
| Debug APK | `./gradlew assembleDebug` | **Passed** |
| Release APK + R8 + `lintVitalRelease` | `./gradlew assembleRelease` | **Passed** - "No issues found"; output is `app/build/outputs/apk/release/app-release.apk` (signed, no longer `-unsigned`) |
| Release APK signature | `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` | **Passed** - one signer, `CN=Android Debug`; `aapt2 dump xmltree` on the shipped APK reports `package="com.example.expensetracker"`, `versionName="1.0.0"` and `usesCleartextTraffic=false`, and release `BuildConfig` has `LAB_DEFAULT_BASE_URL = ""`, `LAB_ALLOWED_HOSTS = ""`, `LAB_ENABLED_BY_DEFAULT = false` |
| Instrumentation APK compile | `./gradlew assembleDebugAndroidTest` | **Passed** - sources compile and package |
| Shipped permission posture | `aapt2 dump xmltree --file AndroidManifest.xml <apk>` | **Re-checked 2026-10-15 on the current build** - debug declares `android.permission.INTERNET` + `android.permission.POST_NOTIFICATIONS` (plus the platform's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) with `usesCleartextTraffic=true`; release declares `INTERNET` only (`app/src/release/AndroidManifest.xml` removes the notification permission, since the endpoint is compiled out) with `usesCleartextTraffic=false`. **Neither variant declares `READ_CALL_LOG`, `WRITE_CALL_LOG` or `READ_PHONE_STATE`** - the call-log fixtures are generated in code, so the permission is not needed and was not added |
| Instrumentation (Compose UI) tests | `am instrument -w com.example.expensetracker.debug.test/androidx.test.runner.AndroidJUnitRunner` | **Failed on the last run** - executed 2026-10-15 against a Genymotion Android 11 virtual device: 18 of 18 Compose assertions failed on selector issues (`useUnmergedTree` is needed for `NavigationBarItem` content descriptions, `assertIsDisplayed()` cannot see below-the-fold rows, and `₹500.00`/`₹35.00` match three nodes). The app itself installed, launched and rendered correctly; the test code needs fixing, not the screens |
| `connectedDebugAndroidTest` | Gradle | **Blocked** - AGP's Unified Test Platform crashes with `FileNotFoundException: Invalid file path` in `DeviceProviderProfileManager.writeToFile` for device names containing " - "; use `adb install -r` + `am instrument` as above |
| Security-test notification on device | `SecurityTestNotificationDeviceTest` | **Not run** - the class has still never executed. The AVD is reachable now, so `adb install -r` + `am instrument -w` would run it; it was not exercised during the 2026-10-17 periodic-generator work, and the notification path is unchanged by it |
| Live Flask server against the contract | `python app.py` + `curl` | **Re-checked 2026-10-15** - the server on the lab host implements all five documented routes. Measured over HTTP on a spare port with a throwaway database: `GET /api/health` 200 `status=ok`, `POST /api/lab/sessions` 201 `lab_run_id=run-0001`, `POST /api/lab/synthetic-events` 201 `received=1` for the security-test fixture and 400 for a non-fixture record, `POST /api/lab/command` 200 `command=null` when nothing is queued. Details in [docs/server-integration.md](docs/server-integration.md) §5 |
| Live server, call-log fixture route | `curl` against `python app.py` (`LAB_PORT=5055`, `LAB_DB=tmp_verify/e2e.sqlite3`) | **Passed 2026-10-15** - submitting the six `syn-call-0001..0006` fixtures returned `{"accepted":6,"duplicates":0,"status":"stored"}` at 201; replaying the identical batch returned `{"accepted":0,"duplicates":6}` at 201 and left exactly 6 rows in `synthetic_call_logs`; a record with `+919876543210` / `source:"DEVICE"` / `synthetic:false` was refused with `{"error":"non-fixture call record rejected"}` at 400 and a `NON_FIXTURE_CALLLOG_REJECTED` event was written while the values themselves were discarded (`count(*) where caller_number not like '+91-90000-%'` = 0). The server also prints the per-record accepted/duplicate summary to its terminal. Same checks scripted in `C:\DailySpendServer\verify_call_logs.py`, all 9 pass |
| Live APK -> Flask round trip | manual | **Verified 2026-10-17** on the `Medium_Phone` AVD against the running server (see the two rows below). Earlier "Not verified" entries for the security-test and expense-triggered paths still stand as *not re-run in this session* |
| Live APK -> Flask, periodic generator | AVD + `python app.py` (default `LAB_DB`, port 5000) | **Passed 2026-10-17** - Settings switch on, *Open lab dashboard*, Start session (`17e695b4`, server 201), Start generating. 18 cycles ran: 14 at batch 6 / 10 s, then Apply changed the cadence to 2 / 20 s with no overlapping loop (next-cycle timestamps stayed 20 s apart). Room `lab_call_log_records` held 92 rows with 92 distinct `eventId`s over 18 distinct `cycleId`s, all `DELIVERED` at `attemptCount=1`; the server held exactly 92 rows for that session (98 total including the six older fixtures); the JSONL recorded a `SYNTHETIC_CALLLOG_CYCLE` then a `SYNTHETIC_CALLLOG_DELIVERY` per cycle, proving persist-before-transmit. The `expenses` table still held 3 rows and none of their values appeared in any payload |
| Live APK, stop semantics | same AVD | **Passed 2026-10-17** - Stop generating returned the card to Stopped with `Next cycle -` immediately; force-stopping the app mid-cycle added no rows across 96 -> 96 while dead, and after relaunch the session read `closed`, generation `Stopped`, cycles `0`, and the dashboard showed "Turn the lab module on in Settings before using this screen". No boot receiver, no work-manager job and no auto-resume exist in the code |
| Server-side retry idempotency, live data | replay of a real batch with `urllib` | **Passed 2026-10-17** - the last eight stored device records were re-POSTed twice verbatim: both replies were `201 {"accepted":0,"duplicates":8,"status":"stored"}` and the table stayed at 102 rows (98 + the 4 records from the two extra cycles that had since landed), with 102 distinct ids |
| Browser dashboard | `GET /dashboard` + scripted DOM checks | **Passed 2026-10-17** against live data - synthetic banner, auto-refresh checkbox off, summary cards (102 stored / 25 batches / 28 duplicate resubmissions / 0 refused / 4 sessions), bars that sum to the total (ANSWERED 36 + MISSED 33 + REJECTED 33 = 102; PERIODIC_GENERATION 96 + EXPENSE_CREATED 6 = 102), session table including `unregistered`, all filters and the event-id search (`syn-call-17e695b4-0018-0` -> 2 rows), reset back to 102, and the row detail dialog with the five fixture guards reported as passing. **Screenshots and wide-viewport layout were not visually confirmed** - the embedded browser viewport stayed at 264 CSS px and `take_screenshot` reported `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`; layout was verified by measurement (`scrollWidth == clientWidth`) instead |
| Browser dashboard, collapsed analysis view | `GET /dashboard` + scripted DOM checks against live data | **Passed 2026-10-17** - default load kept only the banner, Refresh, the auto-refresh switch, the count line and the 186-row record table visible (`analysis`/`filters` both `hidden`, checkbox unchecked), and just one network request (`/api/lab/synthetic-call-logs`) was issued; ticking **Show analysis** revealed 5 cards (186 stored / 40 batches / 34 duplicates / 0 refused / 5 sessions), 3+3+2 bars, 6 session rows and the filter controls, made a row click open the detail dialog with its fixture-guard list, and added the "Click a row for the detail view." sentence; unticking closed the dialog, reset the filter fields and dropped the clickable cursor. **Screenshot still unavailable** (`NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`, viewport 279x554), and the 58-check `verify_dashboard.py` asserts the elements exist in the HTML rather than the toggle's visibility semantics |
| Records table trimmed to 7 columns | scripted DOM checks on `GET /dashboard`, live data | **Passed 2026-10-17** - the Event id, Session and Trigger cells were removed from `renderRecords` and their `<th>`s from the `<thead>`. The rendered header is now Caller, Recipient, Direction, Type, Started (UTC), Duration, Received (UTC); each of the 186 live rows has exactly 7 `<td>`s; no `syn-call-` id or session uuid appears in the table body; all 186 rows still carry the `SYNTH` chip (moved into the caller cell); a row click still opens the detail dialog showing `Event id syn-call-07a07da0-0014-05`, `Session 07a07da0-…` and `Trigger PERIODIC_GENERATION`; the empty-state `colSpan` was corrected to 7; and the page has no horizontal overflow (`documentElement.scrollWidth == clientWidth` at the 264 CSS px test viewport). `verify_dashboard.py` 58/58 and `verify_call_logs.py` 9/9 were re-run after the edit; screenshot still unavailable |
| Examiner-side acquisition of the device call log | `adb root` + `adb pull` + `sqlite3` on the copy | **Executed 2026-10-21** on `Medium_Phone` - provider database pulled with its journal and hashed (`calllog.db` 45,056 bytes, SHA-256 `220b86e8…5410fc2a`, `PRAGMA integrity_check` = `ok`), 25 rows exported to CSV/JSON with a manifest, cross-checked against `content query` before the pull. 24 rows are the examiner-seeded `+91-90000-` block; row `_id 26` (`6505551212`, MISSED, `2026-10-21T02:24:56Z`) arrived from the emulator's telephony layer. `source_package` is NULL on all rows, and the APK holds no call-log permission, so none of it is attributable to DailySpend. Evidence lives in `C:\DailySpendServer\evidence\emulator_calllog_20261021T023356Z\`; **nothing from this acquisition was sent to the lab server** - see [docs/device-data-acquisition.md](docs/device-data-acquisition.md) |
| Lab APK and server, live re-check | emulator boot + Settings **Ping** | **Passed 2026-10-21** - `Medium_Phone` booted, `com.example.expensetracker.debug` launched, Settings showed the lab switch on with endpoint `http://10.0.2.2:5000/` and session `closed`; Ping returned `reachable - HTTP 200 status=ok labMode=true - 2026-10-21T02:22:06Z` on screen while `/api/lab/state` reported `sessions: 9, events: 60, synthetic_call_logs: 6`. The Room table `lab_call_log_records` was empty, so the six server rows are the 2026-10-17 deliveries and nothing new was transmitted |

The five instrumentation classes (`DashboardUiTest`, `ExpenseEntryUiTest`, `HistoryUiTest`,
`SettingsLabUiTest`, `SecurityTestNotificationDeviceTest`) cover dashboard rendering, expense
creation and validation, transaction history with search/filter/delete, starting a lab session, the
server connection status line, and the security-test notification trail. Their assertions were
written before any emulator existed; the last execution on Genymotion turned 18 of them red for the
selector reasons above, so treat the UI suite as **failing and under repair**, not as passing.

### Behaviour verified by the passing JVM suite

- **Works with no server.** The tracker's tests (`ExpenseRepositoryTest`, `ExpenseAggregatorTest`,
  `ExpenseValidatorTest`, `DateUtilsTest`, `MoneyTest`) never construct a network client at all, and
  `OkHttpLabApiClientTest` asserts that with the lab flag off all five API calls return `Disabled`
  while `server.requestCount == 0`, and `LabCallLogSyncTest` asserts that the expense-creation hook
  returns without touching Room, the JSONL log, the logger or a socket when the switch is off.
- **Only synthetic events leave the app.** `LabPayloadBuilderTest`, `SyntheticExpenseFactoryTest`
  and `OkHttpLabApiClientTest` prove a non-fixture record aborts the send before any socket opens,
  and `LabSessionManagerTest` proves generated batches are fixture-only and that no lab payload
  contains a stored expense description.
- **The call-log fixtures cannot carry a real call.** `SyntheticCallLogFactoryTest` asserts every
  id is in the `syn-call-` namespace and both numbers are in the reserved `+91-90000-` block, and
  that a record with a real-shaped number, a foreign id or `source: "DEVICE"` fails the guard.
  `OkHttpLabApiClientTest` then proves such a record aborts with `InvalidPayload` and
  `server.requestCount == 0`. `LabCallLogSyncTest` covers dedupe (the second expense sends nothing),
  partial re-sends, server-reported duplicates, every failure kind leaving the batch queued for the
  next app action instead of marking it sent, the in-flight guard against two saves at once, and that
  *Clear lab artefacts* resets the set so the demo repeats.
- **The periodic generator stays inside the lab.** `SyntheticCallLogGeneratorTest` asserts every cycle
  produces fresh `syn-call-<session>-<cycle>-<index>` ids even when the caller/callee/duration template
  repeats, that ids never repeat within a run, and that batch and interval are clamped to 1-20 and
  5-600 s. `LabPeriodicGenerationTest` covers the loop itself: nothing is scheduled until the user
  presses Start, exactly one loop runs, a second Start is refused while one is running, Apply retargets
  the cadence without spawning a second loop, Stop cancels the pending delay, generation refuses to run
  with no open session or with the lab switch off, records are written to Room before any socket is
  opened, and a delivery failure leaves the row `PENDING` with the same id for the next attempt rather
  than minting a new one. `LabCallLogSyncTest` and `LabSessionManagerTest` cover the retry/dedupe
  contract those ids depend on.
- **Network failures do not crash the app.** Every outcome is a sealed `LabApiResult`
  (`Timeout`, `NetworkFailure`, `HttpError`, `InvalidPayload`, `Disabled`), exercised against
  MockWebServer with delayed bodies and abrupt disconnects, and the full offline session is covered
  end-to-end in `LabSessionManagerTest`.
- **Remote commands are constrained.** `LabCommandParserTest` rejects unknown names, hostile strings
  (`SHELL`, `rm -rf /`, `READ_SMS`, `INSTALL_APK`), any parameters, undated commands and commands
  issued with no active session.
- **The single-event security test tells the truth about the server.** `LabSessionManagerTest`
  covers the *Run Security Test* path for HTTP 2xx, HTTP error, timeout, network failure, malformed
  reply, endpoint-refused and lab-offline outcomes; only the 2xx case is reported as `DELIVERED`, and
  a second tap while a request is in flight is rejected by an atomic in-flight guard without opening
  a socket. The test never retries, even when *Allow one retry* is on.
- **The notification trail is archived whatever the device does.** `SecurityTestNotificationTest` and
  `LabSessionManagerTest` assert the notification title/text and the `SECURITY_TEST_NOTIFICATION`
  Room row, including the case where the operating system refuses to show it (`"posted":false`) -
  the evidence row is still written.
- **Only lab fixtures can ever be announced.** The notification text is built from the outcome
  summary plus `SyntheticExpenseFactory.SECURITY_TEST_FIXTURE_ID`; no expense table read is involved.

Manual exercising on an emulator is now partially closed: on 2026-10-17 the security-lab dashboard was
driven by hand on the `Medium_Phone` AVD (session start/stop, generation start/stop, cadence Apply,
validation errors, both filter chips, statistics and the record list) and the results are in the table
above. The expense entry, history and settings screens were last exercised by hand on 2026-10-16, and
the automated Compose suite remains red for the selector reasons above.

## What the lab module does - and does not do

Behaviour A-E of the lab brief, plus the synthetic call-log fixture sync, are implemented behind the
Settings switch, which defaults to *off* and is never turned on by the app itself:

- a session is started only by pressing **Start test session**, gets a fresh UUID, and is stamped
  with a start time;
- payloads are built from eight hard-coded fixtures in `lab/SyntheticExpenseFactory.kt` - every
  description starts with `Lab fixture - ` and every id with `syn-`;
- `lab/LabPayloadBuilder.kt` and `net/OkHttpLabApiClient.kt` both re-check those markers, and the
  only record type either one accepts is `SyntheticExpenseRecord`, so a real `Expense` row cannot be
  passed to the network layer by construction;
- server commands are validated against a four-item allowlist
  (`SHOW_LAB_STATUS`, `CREATE_SYNTHETIC_EVENT`, `WRITE_TEST_MARKER`, `END_LAB_SESSION`); unknown
  names, any parameters and undated commands are rejected and logged;
- a session works with the server down: failures are recorded locally instead of thrown, and a retry
  happens only if the user switched on *Allow one retry*;
- **Run Security Test** sends exactly one hard-coded fixture (`syn-fixture-demo-001`, event type
  `SYNTHETIC_TRANSACTION_VIEWED`, purpose `MOBILE_FORENSICS_TEST`) to `POST /api/lab/synthetic-events`
  and is reported as delivered only after a 2xx with valid lab JSON; it never runs by itself, never
  retries, and a second tap during an in-flight request is refused;
- after a **newly created** expense has been accepted by Room, `ExpenseEditViewModel` calls
  `LabSessionManager.onExpenseCreated()`. With the switch off that returns immediately. With it on,
  the six hard-coded rows in `lab/SyntheticCallLogFactory.kt` that this install has not already
  submitted go to `POST /api/lab/synthetic-call-logs`. They are invented values - ids
  `syn-call-0001..0006`, numbers in the reserved `+91-90000-` block - read from no provider: the app
  holds no call-log permission, never queries `CallLog.Calls`, and never writes to it. Editing an
  existing expense and any failed save trigger nothing, the expense screen is unchanged (no button,
  banner or status line), and `ProcessedRecordStore` keeps the already-accepted ids so the second
  save sends nothing. If the server is down the ids stay unmarked and the batch is offered again on
  the next app action - that is the offline-retention and retry case;
- that test also posts a notification **owned by DailySpend** (`lab/AndroidLabResultNotifier.kt`)
  with the same result text, and archives title, message, channel, notification id, UTC time, HTTP
  status and whether the system accepted it as a `SECURITY_TEST_NOTIFICATION` row. On Android 13+
  the platform demands `POST_NOTIFICATIONS` before anything can be shown, so Settings grows an
  **Allow lab result notifications** button under *Run Security Test* - the only place the dialog is
  ever raised, and only while the lab switch is on. Declining changes nothing else: the request still
  goes out, the artefacts are still written, and the archived row records `posted: false` instead of
  pretending a tray entry existed;
- each action writes one Room row in `security_lab_events` and one line in
  `files/lab_events/lab_forensic_events.jsonl`.

There is no background worker, no receiver, no startup hook, no hidden icon, no accessibility
service, no keystroke or clipboard capture, no SMS/contact/call-log/location/camera/microphone
permission, no `NotificationListenerService` or any other special-access service (the app never
reads notifications posted by other applications), and no code path that executes server-supplied
text. The call-log rows the lab transmits are the opposite of that: they are constants in
`lab/SyntheticCallLogFactory.kt`, and because no real call history is involved there is nothing for a
permission to grant - so none is requested. The debug manifest declares two permissions - `INTERNET`
and `POST_NOTIFICATIONS`, both serving
only the lab module - and the release manifest merger drops `POST_NOTIFICATIONS`, because the release
build compiles the endpoint away so no lab transfer can ever succeed. Verified on the shipped files
with `aapt2 dump xmltree`: debug lists `INTERNET` + `POST_NOTIFICATIONS`, release lists `INTERNET`
alone. See [docs/security-assumptions-and-limitations.md](docs/security-assumptions-and-limitations.md).

## Project layout

```
app/src/main/java/com/example/expensetracker/
  DailySpendApplication.kt      process entry, holds AppContainer
  MainActivity.kt               single Compose activity
  data/local/                   AppDatabase, ExpenseDao, SecurityLabEventDao
  data/model/                   Expense, SecurityLabEvent, Catalogs (categories/methods)
  data/repository/              ExpenseRepository, SecurityLabRepository (+ sealed results)
  di/AppContainer.kt            hand-written dependency graph
  domain/                       ExpenseValidator, ExpenseAggregator
  lab/                          LabConfig, LabSessionManager, SyntheticExpenseFactory,
                                SyntheticCallLogFactory, ProcessedRecordStore,
                                LabPayloadBuilder, LabCommand(+Parser), ForensicEventLog,
                                LabSettingsStore, LabEventPayloads, LabLogger,
                                SecurityTestNotification, AndroidLabResultNotifier
  net/                          LabApiModels, OkHttpLabApiClient, LabEndpointPolicy
  ui/                           DailySpendApp (navigation), dashboard/, expenses/,
                                history/, settings/, common/, theme/, navigation/
  util/                         Money, DateUtils
app/src/test/                   143 JVM unit tests + fakes (FakeDaos, FakeLabApiClient)
app/src/androidTest/            Compose UI tests, device tests, UiTestSupport
app/src/release/AndroidManifest.xml   merger override that drops POST_NOTIFICATIONS from release
app/schemas/                    exported Room schema (version 1)
docs/                           architecture, forensics guide, security notes, server contract
```

## Known limitations

- The Compose UI tests were executed once, on a Genymotion Android 11 device, and all 18 failed on
  selector problems in the test code (unmerged-tree content descriptions, below-the-fold
  `assertIsDisplayed()`, ambiguous price matches). They are compiled and packaged, but red until
  repaired. The device tests added for the security-test notification have not been executed, because
  that virtual device is not reachable now.
- The live APK -> Flask round trip is **not** proven. The server on the lab host now implements the
  five documented routes and answers them correctly over HTTP, but no device has pressed **Run
  Security Test** or saved a new expense against it since these changes - the Genymotion virtual
  device was not reachable (`adb devices` returned nothing). See
  [docs/server-integration.md](docs/server-integration.md) §5.
- The Python Flask lab server is deliberately **not** part of this repository; the client-side
  contract, request/response shapes and a suggested Flask skeleton are documented in
  [docs/server-integration.md](docs/server-integration.md). The running copy simply has to answer the
  five paths in §2 - §5 records what the current one does.
- The synthetic call-log demonstration **does not touch the system call log**. Android's
  `CallLog.Calls` provider is neither read nor written, and on a modern device writing it is not
  permitted for a third-party app at all, so the fixtures live in the app's own lab tables
  (`security_lab_events`, plus the `lab_processed_fixture_ids` preference) and in the server's
  `synthetic_call_logs` table. An examiner acquiring the device therefore sees a realistic
  exfiltration *artefact shape* - a code-generated record set, an outbound POST, a receipt and a
  server row - but the numbers themselves are dummy values in the reserved `+91-90000-` block and
  must never be presented as recovered call history.
- Because the sync rides on the expense-save success path, it inherits that path's limits: an expense
  saved while the app process is being killed can skip its sync, and the retry only happens on the
  next app interaction - there is deliberately no worker or receiver to make it prompt.
- Local artefacts are app-private and writable by the app itself: they are useful teaching material
  for an acquisition exercise, **not** tamper-proof evidence.
- Only one rotation file is kept for the JSONL log, and Room rows can be cleared from the Settings
  card, so the log is bounded by design and will drop the oldest records under sustained use.
