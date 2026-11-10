# Server integration

The client half of the integration is finished and tested; the Python Flask server is deliberately
**not** part of this repository (it belongs to the lab infrastructure). This file is the contract the
client implements, plus a skeleton you can run for the exercise.

## 1. Transport facts

| Item | Value |
| --- | --- |
| Scheme | `http` for the emulator loopback lab hosts only (`10.0.2.2`, `10.0.3.2`, `localhost`, `127.0.0.1`); every other host must be `https` |
| Base URL | configured on Settings, default `http://10.0.2.2:5000/` in debug builds, empty in release builds |
| Headers sent | `Accept: application/json`, `Content-Type: application/json` on POST, `X-DailySpend-Lab: 1` |
| Timeouts | 5 000 ms connect, read and write (`OkHttpClient` built in `di/AppContainer.kt`) |
| Automatic retry | none (`retryOnConnectionFailure(false)`); a second attempt only happens when the user turned on *Allow one retry* and presses the button again |
| Redirects | not followed (`followRedirects(false)`), so a redirect cannot move the lab traffic elsewhere |
| Body encoding | UTF-8 JSON, `kotlinx.serialization`, unknown response fields are ignored |

Reaching the host from the emulator: `10.0.2.2` maps to the host loopback. `localhost` inside the
emulator is the emulator itself, so a Flask server bound to `127.0.0.1:5000` on the host is reachable
only as `http://10.0.2.2:5000/`. Genymotion's guest reaches its host on the VirtualBox default
gateway, `10.0.3.2`, so `http://10.0.3.2:5000/` is the endpoint to type on a Genymotion device. Bind
to `0.0.0.0` (or use `--host 0.0.0.0`) if you also want to reach it from a physical device on the
same network.

## 2. Endpoints

All five are defined in `net/LabApiModels.kt` as `LabEndpoints`.

### `GET /api/health`

```json
{ "status": "ok", "lab_mode": true, "server_time_utc": "2026-10-15T09:12:44Z" }
```

`status` must be a non-blank string for the client to report the server as reachable. The Settings
status line shows `HTTP <code> status=<status> labMode=<lab_mode>` plus a UTC attempt timestamp.

### `POST /api/lab/sessions`

Request:

```json
{
  "session_id": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11",
  "started_at_utc": "2026-10-15T09:10:02Z",
  "app_version": "1.0.0",
  "lab_build": true
}
```

Response:

```json
{ "accepted": true, "lab_run_id": "run-0007" }
```

### `POST /api/lab/synthetic-events`

Request - note that every record is a fixture; nothing from the `expenses` table can appear here:

```json
{
  "session_id": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11",
  "app_version": "1.0.0",
  "prepared_at_utc": "2026-10-15T09:11:30Z",
  "events": [
    {
      "event_id": "syn-6d0f4d2e-9a1b-4c3f-8f21-7c2b5a1f0d99",
      "synthetic": true,
      "amount_minor": 12500,
      "currency": "INR",
      "category": "FOOD",
      "description": "Lab fixture - canteen counter A",
      "occurred_at_utc": "2026-10-15T09:10:02Z"
    }
  ]
}
```

Response:

```json
{ "received": 5, "status": "stored" }
```

#### The *Run Security Test* button

The single-event test reuses this exact endpoint and body shape - it is the same call with an
`events` array of length one and the fixed fixture from `lab/SyntheticExpenseFactory.kt`. No new path,
field or response format is introduced:

```json
{
  "session_id": "lab-sess-3f2a1c94",
  "app_version": "1.0.0",
  "prepared_at_utc": "2026-10-15T09:20:11Z",
  "events": [
    {
      "event_id": "syn-fixture-demo-001",
      "synthetic": true,
      "amount_minor": 10000,
      "currency": "INR",
      "category": "OTHER",
      "description": "Lab fixture - SYNTHETIC_TRANSACTION_VIEWED purpose=MOBILE_FORENSICS_TEST",
      "occurred_at_utc": "2026-10-15T09:20:11Z"
    }
  ]
}
```

`{ "received": 1, "status": "stored" }` is the only reply that lets the app say *delivered*; anything
else is stored as a failure with its own delivery status. The event type
`SYNTHETIC_TRANSACTION_VIEWED` and the purpose `MOBILE_FORENSICS_TEST` travel inside the fixture's
`description`, because the documented ingestion schema has no separate `event_type`/`purpose` field -
adding one would change the contract for every other caller, so the lab metadata stays in the field
the server already parses.

### `POST /api/lab/synthetic-call-logs`

Added for the call-log half of the exercise. The device sends this after a **new** expense has been
saved successfully while the lab switch is on; the ordinary expense flow is unchanged and no
call-log permission is requested. The `records` array is built only from
`lab/SyntheticCallLogFactory.kt` - six hard-coded rows with ids in the `syn-call-` namespace and both
numbers inside the reserved `+91-90000-` test block. Nothing from Android's
`CallLog.Calls` provider is read, and the provider is never written to.

Request:

```json
{
  "session_id": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11",
  "app_version": "1.0.0",
  "prepared_at_utc": "2026-10-15T09:31:07Z",
  "trigger": "EXPENSE_CREATED",
  "records": [
    {
      "record_id": "syn-call-0001",
      "caller_number": "+91-90000-00001",
      "callee_number": "+91-90000-00002",
      "direction": "INCOMING",
      "call_type": "ANSWERED",
      "started_at_utc": "2026-10-12T09:15:00Z",
      "duration_seconds": 184,
      "source": "SYNTHETIC_LAB",
      "synthetic": true
    }
  ]
}
```

Response:

```json
{ "accepted": 6, "duplicates": 0, "status": "stored" }
```

Dedupe works on two levels: the client keeps the ids the server has already accepted in
`SharedPreferences` (`lab/ProcessedRecordStore.kt`, key `lab_processed_fixture_ids`) and only offers
the rest, and the server keys its `synthetic_call_logs` table on `record_id`, so a replayed batch
comes back `accepted: 0, duplicates: 6` instead of storing a second copy. A send is only marked done
after a 2xx; a refused, timed-out or unreachable batch stays pending and is retried on the next
app interaction. `trigger` is `EXPENSE_CREATED` for the automatic path and `MANUAL` where a test or
the Settings action names it.

### `POST /api/lab/command`

Request: `{ "session_id": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11" }`

Response for an accepted action:

```json
{ "command": "WRITE_TEST_MARKER", "issued_at_utc": "2026-10-15T09:12:03Z", "parameters": {} }
```

Rules enforced by `lab/LabCommandParser.kt` on the client:

- `command` must be one of `SHOW_LAB_STATUS`, `CREATE_SYNTHETIC_EVENT`, `WRITE_TEST_MARKER`,
  `END_LAB_SESSION`. Anything else becomes `LAB_COMMAND_REJECTED`.
- `parameters` must be empty - the allowlisted actions take no arguments, which is precisely what
  keeps this from being a remote command channel.
- `issued_at_utc` must be present and non-blank.
- A command is only executed while a lab session is active on the device.
- The client never evaluates the response text: it maps `command` onto a closed enum and runs the
  matching local branch.

## 3. How each outcome is recorded on the device

| Outcome | `LabApiResult` | Event type | Delivery status |
| --- | --- | --- | --- |
| 2xx with valid JSON | `Success` | `TRANSFER_RESPONSE` | `DELIVERED` |
| 4xx/5xx | `HttpError` | `TRANSFER_FAILURE` | `FAILED_HTTP` |
| Socket timeout | `Timeout` | `TRANSFER_FAILURE` | `FAILED_TIMEOUT` |
| Connection refused / DNS / IO error | `NetworkFailure` | `TRANSFER_FAILURE` | `FAILED_NETWORK` |
| Non-JSON or shapeless body | `InvalidPayload` | `TRANSFER_FAILURE` | `INVALID_PAYLOAD` |
| Lab switch off or endpoint refused | `Disabled` | `TRANSFER_FAILURE` (transfer) / no request at all | `FAILED_NETWORK` with the reason |
| Batch contains a non-fixture record | `InvalidPayload` before any socket is opened | `TRANSFER_FAILURE` | `REJECTED` |
| Any security-test outcome (success or failure) | - | `SECURITY_TEST_NOTIFICATION` | `LOCAL_ONLY`, payload carries the notification title, message, channel, id, UTC time, HTTP status and `posted` |
| Call-log fixtures accepted by the server | `Success` | `SYNTHETIC_CALLLOG_SYNC` | `DELIVERED`, payload carries trigger, `fixtureCount`, `alreadySentCount`, `sentRecordIds`, `accepted`, `duplicates` |
| Call-log batch with nothing new to send | no request | `SYNTHETIC_CALLLOG_SYNC` | `LOCAL_ONLY`, `fixtureCount: 0` |
| Call-log send refused or unreachable | `HttpError` / `NetworkFailure` / `Timeout` / `InvalidPayload` | `TRANSFER_FAILURE` (`kind: SYNTHETIC_CALLLOG`) | matching failure status; the ids stay unmarked for the next attempt |

`LabApiResult` is a sealed interface, so none of these throws at the caller and none can be ignored
by accident.

## 4. Reference Flask skeleton

`lab_server/app.py` (not shipped here; drop it into a separate virtualenv):

```python
import time
import uuid
from flask import Flask, jsonify, request

ALLOWED_COMMANDS = {
    "SHOW_LAB_STATUS",
    "CREATE_SYNTHETIC_EVENT",
    "WRITE_TEST_MARKER",
    "END_LAB_SESSION",
}

app = Flask(__name__)
queue: list[str] = []          # commands the instructor has queued for the device
runs: dict[str, dict] = {}
ingested: list[dict] = []
call_logs: dict[str, dict] = {}   # keyed on record_id: the fixture id is the dedupe key


def is_fixture(event: dict) -> bool:
    return bool(event.get("synthetic")) and str(event.get("description", "")).startswith("Lab fixture - ") \
        and str(event.get("event_id", "")).startswith("syn-")


def is_call_fixture(record: dict) -> bool:
    return (bool(record.get("synthetic")) and record.get("source") == "SYNTHETIC_LAB"
            and str(record.get("record_id", "")).startswith("syn-call-")
            and str(record.get("caller_number", "")).startswith("+91-90000-")
            and str(record.get("callee_number", "")).startswith("+91-90000-"))


@app.before_request
def require_lab_header():
    if request.headers.get("X-DailySpend-Lab") != "1":
        return jsonify({"error": "missing lab header"}), 400


@app.get("/api/health")
def health():
    return jsonify({"status": "ok", "lab_mode": True,
                    "server_time_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})


@app.post("/api/lab/sessions")
def register_session():
    body = request.get_json(force=True, silent=True) or {}
    session_id = body.get("session_id")
    if not session_id:
        return jsonify({"error": "session_id required"}), 400
    runs[session_id] = {"started_at_utc": body.get("started_at_utc"), "app_version": body.get("app_version")}
    return jsonify({"accepted": True, "lab_run_id": f"run-{len(runs):04d}"}), 201


@app.post("/api/lab/synthetic-events")
def ingest():
    body = request.get_json(force=True, silent=True) or {}
    events = body.get("events") or []
    # Defence in depth: refuse anything that is not a lab fixture.
    if not events or not all(is_fixture(e) for e in events):
        return jsonify({"error": "payload contained a non-fixture record"}), 422
    ingested.extend(events)
    return jsonify({"received": len(events), "status": "stored"}), 202


@app.post("/api/lab/synthetic-call-logs")
def ingest_call_logs():
    body = request.get_json(force=True, silent=True) or {}
    records = body.get("records") or []
    if not records or not all(is_call_fixture(r) for r in records):
        return jsonify({"error": "non-fixture call record rejected"}), 400
    # record_id is the dedupe key, so a replayed batch reports duplicates instead of re-storing.
    new = [r for r in records if r["record_id"] not in call_logs]
    for record in new:
        call_logs[record["record_id"]] = record
    return jsonify({"accepted": len(new), "duplicates": len(records) - len(new), "status": "stored"}), 201


@app.post("/api/lab/command")
def next_command():
    if not queue:
        return jsonify({"command": None, "parameters": {}}), 204
    name = queue.pop(0)
    return jsonify({"command": name, "issued_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "parameters": {}})


@app.post("/api/lab/queue")            # instructor helper, not called by the app
def queue_command():
    body = request.get_json(force=True, silent=True) or {}
    name = body.get("command")
    if name not in ALLOWED_COMMANDS:   # try "SHELL rm -rf /" to exercise the client's rejection path
        return jsonify({"error": "command outside the allowlist"}), 400
    queue.append(name)
    return jsonify({"queued": name, "pending": len(queue)}), 202


@app.get("/api/lab/state")
def state():
    return jsonify({"sessions": len(runs), "events": len(ingested), "queue": queue,
                    "synthetic_call_logs": len(call_logs)})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
```

Run and exercise it:

```bash
python -m venv .venv && source .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install flask
python app.py                                        # listens on 0.0.0.0:5000

curl -s -H 'X-DailySpend-Lab: 1' http://127.0.0.1:5000/api/health
curl -s -X POST -H 'Content-Type: application/json' -H 'X-DailySpend-Lab: 1' \
     -d '{"command":"WRITE_TEST_MARKER"}' http://127.0.0.1:5000/api/lab/queue
# then, from the emulator shell, confirm reachability before using Settings:
adb shell curl -s -H 'X-DailySpend-Lab: 1' http://10.0.2.2:5000/api/health
```

Queue a command and press *Fetch allowlisted test command*: the device records `COMMAND_RECEIVED` and
runs the matching local branch. Note that both this skeleton and the server in §5 reject a name
outside the four-value allowlist with HTTP 400, so the `LAB_COMMAND_REJECTED` artefact on the device
is exercised by the JVM suite rather than by the live server - `LabCommandParserTest` feeds the
parser `SHELL`, `rm -rf /` and friends and asserts each one is refused, and
`OkHttpLabApiClientTest.a hostile command string is still only data` proves the transport decodes the
string without acting on it. That is the expected forensic artefact for "the device cannot be ordered
around".

## 5. The server that runs on the lab host (checked 2026-10-15)

The instructor's copy used to serve only `/` and `/health`, so every client call came back
`HTTP 404` and the device stored `TRANSFER_FAILURE` / `FAILED_HTTP`. It now implements §2. Measured
over real HTTP against that code (`python app.py`, spare port, throwaway database):

| Request | Status | Body |
| --- | --- | --- |
| `GET /api/health` | 200 | `{"status": "ok", "lab_mode": true, "server_time_utc": "2026-10-15T07:55:02Z"}` |
| `POST /api/lab/sessions` | 201 | `{"accepted": true, "lab_run_id": "run-0001"}` |
| `POST /api/lab/synthetic-events` (the `syn-fixture-demo-001` record) | 201 | `{"received": 1, "status": "stored"}` |
| `POST /api/lab/synthetic-events` (a record without the fixture markers) | 400 | `{"error": "non-fixture record rejected", "received": 0, "status": "rejected"}` |
| `POST /api/lab/command` (queue empty) | 200 | `{"command": null, "issued_at_utc": null, "parameters": {}}` |
| `POST /api/lab/command` (one action queued) | 200 | `{"command": "SHOW_LAB_STATUS", "issued_at_utc": "2026-10-15T07:55:03Z", "parameters": {}}` |
| `POST /api/lab/synthetic-call-logs` (the six fixtures) | 201 | `{"accepted": 6, "duplicates": 0, "status": "stored"}` |
| `POST /api/lab/synthetic-call-logs` (same batch again) | 201 | `{"accepted": 0, "duplicates": 6, "status": "stored"}` |
| `POST /api/lab/synthetic-call-logs` (a real-shaped number) | 400 | `{"accepted": 0, "error": "non-fixture call record rejected", "status": "rejected"}` |
| `GET /api/lab/synthetic-call-logs` | 200 | `{"call_logs": [...6 rows...], "count": 6}` |

Four things about that implementation are worth knowing before the class:

- **The fixture guard is server-side too.** Ingestion accepts a record only if `event_id` starts
  with `syn-`, `synthetic` is `true` and `description` starts with `Lab fixture - ` - the same test
  `lab/SyntheticExpenseFactory.kt` applies before the socket is opened. A refused batch is counted in
  a `NON_FIXTURE_REJECTED` row and the record itself is never stored, which is the artefact for
  "someone tried to push a real expense".
- **The call-log table can only ever hold fixtures.** `synthetic_call_logs` is keyed on `record_id`
  and a row is written only if `record_id` starts with `syn-call-`, `synthetic` is `true`, `source`
  is `SYNTHETIC_LAB` and *both* numbers start with `+91-90000-`. The rejected submission above stored
  zero rows and left `count(*) where caller_number not like '+91-90000-%'` at 0; the refusal is
  logged as `NON_FIXTURE_CALLLOG_REJECTED` with the values themselves discarded. So the table shows
  what a call-log exfiltration artefact looks like without ever containing a real call.
- **The command channel selects, it does not instruct.** `POST /api/lab/command` returns one of the
  four `LabCommand` names with `parameters` always empty, and an empty queue returns
  `"command": null` at HTTP 200 rather than a 204, because the client needs parseable JSON to log
  "Server returned no command". `POST /api/lab/queue {"command":"WRITE_TEST_MARKER"}` is the
  instructor helper; it rejects any name outside the allowlist with 400.
- **`POST /api/lab/command` changed meaning.** It is now the issuing side of the contract. The old
  handler, which logged a command the instructor said had run, moved to
  `POST /api/lab/executions` unchanged; `/`, `/health`, `/api/lab/session/start` and
  `GET /api/lab/events` all still work for older worksheets. `GET /api/lab/state` reports session,
  event and queue counts.

Start and exercise it:

```bash
cd C:/DailySpendServer
.venv/Scripts/activate
python app.py                      # 0.0.0.0:5000, sqlite file in the working directory

curl -s -H 'X-DailySpend-Lab: 1' http://127.0.0.1:5000/api/health
curl -s -X POST -H 'Content-Type: application/json' \
     -d '{"command":"SHOW_LAB_STATUS"}' http://127.0.0.1:5000/api/lab/queue
curl -s -X POST -H 'Content-Type: application/json' \
     -d '{"session_id":"probe"}' http://127.0.0.1:5000/api/lab/command
curl -s 'http://127.0.0.1:5000/api/lab/events?session_id=probe'
curl -s http://127.0.0.1:5000/api/lab/synthetic-call-logs   # the stored fixture rows
```

`C:\DailySpendServer\verify_call_logs.py` exercises the call-log route against a throwaway database
using Flask's own test client - accept, duplicate, real-shaped-number refusal, empty-batch refusal,
read-back and event-trail checks - without opening a port. Run it with
`.venv/Scripts/python.exe verify_call_logs.py`.

`LAB_DB` and `LAB_PORT` override the database path and the port, which is how the table above was
produced without touching the live `dailyspend_lab.sqlite3` or the running process.

What is still **not** proven: no device has pressed **Run Security Test** against this server since
the change, and no device has yet saved an expense to trigger the call-log sync - the Genymotion
virtual device was not running when the work above was verified, so `adb devices` listed nothing and
the instrumented suite could not execute. The table rows above were measured from the host with
`curl`, which proves the server side of the wire and nothing about the app's own behaviour on a
handset. The `422` in §4 and the `400` here are both acceptable refusals - the client only ever
reports *delivered* on a 2xx with valid lab JSON.

## 6. Client-side tests for this contract

`app/src/test/java/com/example/expensetracker/net/OkHttpLabApiClientTest.kt` runs the client against
OkHttp `MockWebServer` (20 tests) and asserts: the health round trip and the `X-DailySpend-Lab`
header, HTTP 500 handling, non-JSON responses, tolerance of unknown fields, a 3-second body delay
against a 250 ms read timeout producing `Timeout`, an abrupt disconnect producing `NetworkFailure`,
that switching the feature flag off produces `Disabled` with `server.requestCount == 0` for all five
endpoints, that a non-lab endpoint is refused, the exact request bodies for session register, batch
ingestion, the security-test fixture and the six call-log fixtures, that a non-fixture batch or a
call record with a real-shaped number aborts before any socket is opened, the command round trip,
and that a hostile `rm -rf /` command is rejected by the parser.

`lab/LabSessionManagerTest.kt` (42 tests) covers the same paths one level up, including a complete
offline session, retry gating, marker placement inside the lab directory only, every
*Run Security Test* outcome, the notification archive row, and Room/JSONL parity.

`lab/LabCallLogSyncTest.kt` (13 tests) and `lab/SyntheticCallLogFactoryTest.kt` (5 tests) cover the
expense-triggered sync: nothing at all happens with the switch off, one batch of six leaves the device
and no expense row is read, the second save sends nothing, a partly-sent set sends only the remainder,
every failure kind leaves the ids queued and the next call retries, server-reported duplicates still
close the local queue, two saves in flight produce one request, `clearProcessedFixtures()` makes the
demo repeatable, and every sync event appears in both Room and the JSONL log.

Total for the JVM suite on this branch: 165 tests, 0 failures, 0 skipped, run for both the debug and
the release variant.
