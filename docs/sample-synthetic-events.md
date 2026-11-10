# Sample synthetic events

Concrete examples of every artefact the lab module produces, so students know what to look for
before they acquire it. The session id used throughout is a stand-in for the random UUID that
`LabSessionManager.startSession()` generates; timestamps are UTC with seconds precision, exactly as
`DateUtils.utcIso` formats them (`yyyy-MM-dd'T'HH:mm:ss'Z'`).

Session id: `3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11`  
App version: `1.0.0` (debug build `com.example.expensetracker.debug`)  
Endpoint: `http://10.0.2.2:5000/`

## 1. The fixture catalogue

`lab/SyntheticExpenseFactory.kt` holds these eight rows hard-coded. Amounts are minor units, so
`12500` is `₹125.00`. Nothing here is read from or written to the `expenses` table.

| `event_id` prefix + random UUID | `amount_minor` | `currency` | `category` | `description` |
| --- | --- | --- | --- | --- |
| `syn-<uuid>` | 12500 | INR | FOOD | Lab fixture - canteen counter A |
| `syn-<uuid>` | 4500 | INR | TRANSPORT | Lab fixture - metro token |
| `syn-<uuid>` | 99900 | INR | SHOPPING | Lab fixture - stationery store |
| `syn-<uuid>` | 150000 | INR | EDUCATION | Lab fixture - course material |
| `syn-<uuid>` | 27500 | INR | OTHER | Lab fixture - misc counter |
| `syn-<uuid>` | 63000 | INR | FOOD | Lab fixture - cafeteria tray |
| `syn-<uuid>` | 3200 | INR | TRANSPORT | Lab fixture - cycle rental |
| `syn-<uuid>` | 210000 | INR | SHOPPING | Lab fixture - electronics kiosk |

A default batch is five records, one minute apart starting at the session start time (`index * 60000`
ms), so the `occurred_at_utc` values are derived from the session, not from the wall clock at
generation time.

## 2. Ingestion payload (body of `POST /api/lab/synthetic-events`)

```json
{
  "session_id": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11",
  "app_version": "1.0.0",
  "prepared_at_utc": "2026-10-15T09:11:30Z",
  "events": [
    { "event_id": "syn-6d0f4d2e-9a1b-4c3f-8f21-7c2b5a1f0d99", "synthetic": true,
      "amount_minor": 12500, "currency": "INR", "category": "FOOD",
      "description": "Lab fixture - canteen counter A", "occurred_at_utc": "2026-10-15T09:10:02Z" },
    { "event_id": "syn-91b2c3d4-5e6f-4a01-b2c3-d4e5f6071829", "synthetic": true,
      "amount_minor": 4500, "currency": "INR", "category": "TRANSPORT",
      "description": "Lab fixture - metro token", "occurred_at_utc": "2026-10-15T09:11:02Z" },
    { "event_id": "syn-0a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d", "synthetic": true,
      "amount_minor": 99900, "currency": "INR", "category": "SHOPPING",
      "description": "Lab fixture - stationery store", "occurred_at_utc": "2026-10-15T09:12:02Z" },
    { "event_id": "syn-1f2e3d4c-5b6a-7988-a9ba-cd1e2f3a4b5c", "synthetic": true,
      "amount_minor": 150000, "currency": "INR", "category": "EDUCATION",
      "description": "Lab fixture - course material", "occurred_at_utc": "2026-10-15T09:13:02Z" },
    { "event_id": "syn-2b3c4d5e-6f70-8192-a3b4-c5d6e7f8091a", "synthetic": true,
      "amount_minor": 27500, "currency": "INR", "category": "OTHER",
      "description": "Lab fixture - misc counter", "occurred_at_utc": "2026-10-15T09:14:02Z" }
  ]
}
```

Every description carries the `Lab fixture - ` prefix and every id the `syn-` namespace. Both
`LabPayloadBuilder` and `OkHttpLabApiClient` verify this before the bytes leave the device.

### The single event behind *Run Security Test*

Same endpoint, same schema, `events` of length one, and an id that never changes so students can grep
for it:

```json
{
  "session_id": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11",
  "app_version": "1.0.0",
  "prepared_at_utc": "2026-10-15T09:20:11Z",
  "events": [
    { "event_id": "syn-fixture-demo-001", "synthetic": true,
      "amount_minor": 10000, "currency": "INR", "category": "OTHER",
      "description": "Lab fixture - SYNTHETIC_TRANSACTION_VIEWED purpose=MOBILE_FORENSICS_TEST",
      "occurred_at_utc": "2026-10-15T09:20:11Z" }
  ]
}
```

## 3. Synthetic call-log fixtures (`POST /api/lab/synthetic-call-logs`)

`lab/SyntheticCallLogFactory.kt` holds these six rows hard-coded. They are the artefact the exercise
is about - a call-history exfiltration shape - and none of them came from the device: the manifest
declares no call-log permission, no code path queries `CallLog.Calls`, and the guard
`SyntheticCallLogFactory.isSynthetic()` refuses any record whose two numbers are not inside the
reserved `+91-90000-` test block. Ids and timestamps are fixed constants, so every run produces this
exact set.

| `record_id` | `caller_number` | `callee_number` | `direction` | `call_type` | `started_at_utc` | `duration_seconds` |
| --- | --- | --- | --- | --- | --- | --- |
| `syn-call-0001` | +91-90000-00001 | +91-90000-00002 | INCOMING | ANSWERED | 2026-10-12T09:15:00Z | 184 |
| `syn-call-0002` | +91-90000-00003 | +91-90000-00002 | INCOMING | MISSED | 2026-10-12T11:02:00Z | 0 |
| `syn-call-0003` | +91-90000-00002 | +91-90000-00004 | OUTGOING | ANSWERED | 2026-10-12T13:40:00Z | 612 |
| `syn-call-0004` | +91-90000-00005 | +91-90000-00002 | INCOMING | REJECTED | 2026-10-12T16:05:00Z | 0 |
| `syn-call-0005` | +91-90000-00002 | +91-90000-00006 | OUTGOING | ANSWERED | 2026-10-12T18:22:00Z | 95 |
| `syn-call-0006` | +91-90000-00007 | +91-90000-00002 | INCOMING | ANSWERED | 2026-10-12T20:47:00Z | 1245 |

Saving a **new** expense while the lab switch is on submits every fixture that has not already been
accepted, so the first send is the whole batch:

```json
{
  "session_id": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11",
  "app_version": "1.0.0",
  "prepared_at_utc": "2026-10-15T09:12:05Z",
  "trigger": "EXPENSE_CREATED",
  "records": [
    { "record_id": "syn-call-0001", "caller_number": "+91-90000-00001",
      "callee_number": "+91-90000-00002", "direction": "INCOMING", "call_type": "ANSWERED",
      "started_at_utc": "2026-10-12T09:15:00Z", "duration_seconds": 184,
      "source": "SYNTHETIC_LAB", "synthetic": true },
    { "record_id": "syn-call-0002", "caller_number": "+91-90000-00003",
      "callee_number": "+91-90000-00002", "direction": "INCOMING", "call_type": "MISSED",
      "started_at_utc": "2026-10-12T11:02:00Z", "duration_seconds": 0,
      "source": "SYNTHETIC_LAB", "synthetic": true },
    { "record_id": "syn-call-0003", "caller_number": "+91-90000-00002",
      "callee_number": "+91-90000-00004", "direction": "OUTGOING", "call_type": "ANSWERED",
      "started_at_utc": "2026-10-12T13:40:00Z", "duration_seconds": 612,
      "source": "SYNTHETIC_LAB", "synthetic": true },
    { "record_id": "syn-call-0004", "caller_number": "+91-90000-00005",
      "callee_number": "+91-90000-00002", "direction": "INCOMING", "call_type": "REJECTED",
      "started_at_utc": "2026-10-12T16:05:00Z", "duration_seconds": 0,
      "source": "SYNTHETIC_LAB", "synthetic": true },
    { "record_id": "syn-call-0005", "caller_number": "+91-90000-00002",
      "callee_number": "+91-90000-00006", "direction": "OUTGOING", "call_type": "ANSWERED",
      "started_at_utc": "2026-10-12T18:22:00Z", "duration_seconds": 95,
      "source": "SYNTHETIC_LAB", "synthetic": true },
    { "record_id": "syn-call-0006", "caller_number": "+91-90000-00007",
      "callee_number": "+91-90000-00002", "direction": "INCOMING", "call_type": "ANSWERED",
      "started_at_utc": "2026-10-12T20:47:00Z", "duration_seconds": 1245,
      "source": "SYNTHETIC_LAB", "synthetic": true }
  ]
}
```

The server answers `201` with `{ "accepted": 6, "duplicates": 0, "status": "stored" }` and the device
marks those six ids processed. Re-send the same batch (or press the Settings button again) and the
`record_id TEXT PRIMARY KEY` on the server side plus the client's `lab_processed_fixture_ids` set make
it a no-op: `{"accepted": 0, "duplicates": 6, "status": "stored"}`. Push a record with a real-looking
number such as `+919876543210` and the batch is refused with `400 non-fixture call record rejected`
and the values are discarded, never inserted - that is the `NON_FIXTURE_CALLLOG_REJECTED` row in the
server's `lab_events` table.

Device artefacts for the same save, as `security_lab_events` rows (`payloadJson` expanded):

```json
// TRANSFER_REQUEST - PENDING, before the socket opens
{ "target": "http://10.0.2.2:5000/api/lab/synthetic-call-logs",
  "recordCount": 6, "synthetic": true }

// SYNTHETIC_CALLLOG_SYNC - DELIVERED
{ "trigger": "EXPENSE_CREATED", "fixtureCount": 6, "alreadySentCount": 0,
  "sentRecordIds": ["syn-call-0001","syn-call-0002","syn-call-0003","syn-call-0004",
                    "syn-call-0005","syn-call-0006"],
  "accepted": 6, "duplicates": 0, "source": "SYNTHETIC_LAB", "synthetic": true }

// SYNTHETIC_CALLLOG_SYNC - LOCAL_ONLY, a second new expense after the batch closed
{ "trigger": "EXPENSE_CREATED", "fixtureCount": 0, "alreadySentCount": 6,
  "sentRecordIds": [], "source": "SYNTHETIC_LAB", "synthetic": true }

// TRANSFER_FAILURE - FAILED_NETWORK, Flask was down when the expense was saved.
// The six ids stay unmarked, so the next app interaction retries the whole batch.
{ "kind": "SYNTHETIC_CALLLOG", "respondedAtUtc": "2026-10-15T09:12:20Z",
  "reason": "IOException: Failed to connect to /10.0.2.2:5000", "attempt": 1,
  "synthetic": true }
```

Server-side read-back, `GET /api/lab/synthetic-call-logs?session_id=3f2a1c94-...`, and the table it
is generated from:

```
sqlite> SELECT record_id, direction, call_type, duration_seconds, source
        ... FROM synthetic_call_logs ORDER BY record_id;
syn-call-0001|INCOMING|ANSWERED|184|SYNTHETIC_LAB
syn-call-0002|INCOMING|MISSED|0|SYNTHETIC_LAB
syn-call-0003|OUTGOING|ANSWERED|612|SYNTHETIC_LAB
syn-call-0004|INCOMING|REJECTED|0|SYNTHETIC_LAB
syn-call-0005|OUTGOING|ANSWERED|95|SYNTHETIC_LAB
syn-call-0006|INCOMING|ANSWERED|1245|SYNTHETIC_LAB

sqlite> SELECT count(*) FROM synthetic_call_logs
        ... WHERE caller_number NOT LIKE '+91-90000-%'
        ...    OR callee_number NOT LIKE '+91-90000-%';
0
```

## 4. Room rows in `security_lab_events`

`payloadJson` is stored compactly; it is expanded here for reading.

| id | sessionId | eventType | eventTimestamp | deliveryStatus |
| --- | --- | --- | --- | --- |
| 1 | 3f2a1c94-...-0d9d1f0e7c11 | `LAB_CONFIG_UPDATED` | 1760518200000 | `LOCAL_ONLY` |
| 2 | 3f2a1c94-...-0d9d1f0e7c11 | `LAB_SESSION_START` | 1760518202000 | `LOCAL_ONLY` |
| 3 | 3f2a1c94-...-0d9d1f0e7c11 | `TRANSFER_FAILURE` | 1760518207000 | `FAILED_NETWORK` |
| 4 | 3f2a1c94-...-0d9d1f0e7c11 | `SYNTHETIC_EVENTS_GENERATED` | 1760518290000 | `LOCAL_ONLY` |
| 5 | 3f2a1c94-...-0d9d1f0e7c11 | `TRANSFER_REQUEST` | 1760518295000 | `PENDING` |
| 6 | 3f2a1c94-...-0d9d1f0e7c11 | `TRANSFER_RESPONSE` | 1760518296000 | `DELIVERED` |
| 7 | 3f2a1c94-...-0d9d1f0e7c11 | `SECURITY_TEST_NOTIFICATION` | 1760518310000 | `LOCAL_ONLY` |
| 8 | 3f2a1c94-...-0d9d1f0e7c11 | `LAB_COMMAND_RECEIVED` | 1760518323000 | `DELIVERED` |
| 9 | 3f2a1c94-...-0d9d1f0e7c11 | `TEST_MARKER_WRITTEN` | 1760518324000 | `LOCAL_ONLY` |
| 10 | 3f2a1c94-...-0d9d1f0e7c11 | `TRANSFER_REQUEST` | 1760518325000 | `PENDING` |
| 11 | 3f2a1c94-...-0d9d1f0e7c11 | `SYNTHETIC_CALLLOG_SYNC` | 1760518326000 | `DELIVERED` |
| 12 | 3f2a1c94-...-0d9d1f0e7c11 | `LAB_SESSION_END` | 1760518400000 | `LOCAL_ONLY` |

Row 3 exists because session registration was attempted while the Flask server was down - the
session still started and stayed usable. Rows 5 and 10 are both `TRANSFER_REQUEST`; only their
`target` differs - row 5 posts to `/api/lab/synthetic-events`, row 10 to
`/api/lab/synthetic-call-logs` after a new expense was saved (see §3).

Selected payloads:

```json
// LAB_SESSION_START
{ "sessionId": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11",
  "startedAtUtc": "2026-10-15T09:10:02Z",
  "appVersion": "1.0.0",
  "syntheticOnly": true }

// SYNTHETIC_EVENTS_GENERATED (record list shortened)
{ "generatedCount": 5,
  "synthetic": true,
  "records": [
    { "syntheticEventId": "syn-6d0f4d2e-9a1b-4c3f-8f21-7c2b5a1f0d99",
      "amountMinor": 12500, "currency": "INR", "category": "FOOD",
      "description": "Lab fixture - canteen counter A",
      "occurredAtUtc": "2026-10-15T09:10:02Z", "synthetic": true } ] }

// TRANSFER_REQUEST
{ "target": "http://10.0.2.2:5000/api/lab/synthetic-events",
  "recordCount": 5,
  "synthetic": true }

// TRANSFER_RESPONSE
{ "kind": "SYNTHETIC_INGEST", "httpStatus": 202,
  "respondedAtUtc": "2026-10-15T09:11:36Z", "reason": "received=5",
  "attempt": 1, "synthetic": true }

// TRANSFER_FAILURE, server down at session start
{ "kind": "SESSION_REGISTER", "respondedAtUtc": "2026-10-15T09:10:07Z",
  "reason": "IOException: Failed to connect to /10.0.2.2:5000 (2026-10-15T09:10:02Z)",
  "attempt": 1, "synthetic": true }

// SECURITY_TEST_NOTIFICATION, Genymotion API 30 - the system showed it
{ "title": "DailySpend security lab: test delivered",
  "message": "Security test delivered: HTTP 202, fixture syn-fixture-demo-001, session 3f2a1c94-... | fixture=syn-fixture-demo-001 http=202 synthetic=true",
  "channelId": "daily_spend_security_lab", "notificationId": 740001,
  "postedAtUtc": "2026-10-15T09:11:50Z", "posted": true, "httpStatus": 202,
  "ownAppOnly": true, "synthetic": true }

// SECURITY_TEST_NOTIFICATION, Android 13+ where POST_NOTIFICATIONS was never granted:
// the row is still written, but it admits the tray entry was not shown
{ "title": "DailySpend security lab: test failed",
  "message": "Security test failed: HTTP 404 | fixture=syn-fixture-demo-001 http=404 synthetic=true",
  "channelId": "daily_spend_security_lab", "notificationId": 740001,
  "postedAtUtc": "2026-10-15T09:12:04Z", "posted": false, "httpStatus": 404,
  "ownAppOnly": true, "synthetic": true }

// LAB_COMMAND_RECEIVED after an allowlisted WRITE_TEST_MARKER
{ "wireName": "WRITE_TEST_MARKER", "outcome": "accepted", "synthetic": true }

// LAB_COMMAND_REJECTED when the server replies with something hostile
{ "wireName": "SHELL", "outcome": "rejected",
  "reason": "Unsupported command 'SHELL'; allowlist is SHOW_LAB_STATUS, CREATE_SYNTHETIC_EVENT, WRITE_TEST_MARKER, END_LAB_SESSION",
  "synthetic": true }

// LAB_SESSION_END - the call-log payloads for rows 10-11 are in §3
{ "sessionId": "3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11",
  "endedAtUtc": "2026-10-15T09:13:20Z", "eventCount": 12 }
```

## 5. JSONL forensic log

`files/lab_events/lab_forensic_events.jsonl`, one object per line, keys exactly as
`ForensicEventRecord` serialises them (`requestStatus` and `detail` are omitted when null, and
`detail` is the event payload truncated to 240 characters). Nine of the twelve lines from the session
in §4 are shown; the notification, command and marker lines are omitted for space:

```jsonl
{"eventId":"b1c6e9d2-4a7f-4f0e-8b11-2c3d4e5f6a70","sessionId":"none","eventType":"LAB_CONFIG_UPDATED","timestampUtc":"2026-10-15T09:09:58Z","synthetic":true,"requestStatus":"LOCAL_ONLY","detail":"{\"activeSession\":null,\"endpoint\":\"http://10.0.2.2:5000/\",\"labEnabled\":true,\"retryEnabled\":false,\"message\":\"Lab settings changed\",\"synthetic\":true}"}
{"eventId":"c2d7e8f9-0a1b-4c2d-9e3f-4a5b6c7d8e91","sessionId":"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11","eventType":"LAB_SESSION_START","timestampUtc":"2026-10-15T09:10:02Z","synthetic":true,"requestStatus":"LOCAL_ONLY","detail":"{\"sessionId\":\"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11\",\"startedAtUtc\":\"2026-10-15T09:10:02Z\",\"appVersion\":\"1.0.0\",\"syntheticOnly\":true}"}
{"eventId":"d3e8f90a-1b2c-4d3e-8f40-5b6c7d8e9f02","sessionId":"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11","eventType":"TRANSFER_FAILURE","timestampUtc":"2026-10-15T09:10:07Z","synthetic":true,"requestStatus":"FAILED_NETWORK","detail":"{\"kind\":\"SESSION_REGISTER\",\"respondedAtUtc\":\"2026-10-15T09:10:07Z\",\"reason\":\"IOException: Failed to connect to /10.0.2.2:5000 (2026-10-15T09:10:02Z)\",\"attempt\":1,\"synthetic\":true}"}
{"eventId":"e4f90a1b-2c3d-4e4f-9a51-6c7d8e9f0a13","sessionId":"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11","eventType":"SYNTHETIC_EVENTS_GENERATED","timestampUtc":"2026-10-15T09:11:30Z","synthetic":true,"requestStatus":"LOCAL_ONLY","detail":"{\"generatedCount\":5,\"synthetic\":true,\"records\":[{\"syntheticEventId\":\"syn-6d0f4d2e-9a1b-4c3f-8f21-7c2b5a1f0d99\",\"amountMinor\":12500,\"curr"}
{"eventId":"f5a01b2c-3d4e-4f5a-8b62-7d8e9f0a1b24","sessionId":"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11","eventType":"TRANSFER_REQUEST","timestampUtc":"2026-10-15T09:11:35Z","synthetic":true,"requestStatus":"PENDING","detail":"{\"target\":\"http://10.0.2.2:5000/api/lab/synthetic-events\",\"recordCount\":5,\"synthetic\":true}"}
{"eventId":"06b12c3d-4e5f-4a6b-9c73-8e9f0a1b2c35","sessionId":"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11","eventType":"TRANSFER_RESPONSE","timestampUtc":"2026-10-15T09:11:36Z","synthetic":true,"requestStatus":"DELIVERED","detail":"{\"kind\":\"SYNTHETIC_INGEST\",\"httpStatus\":202,\"respondedAtUtc\":\"2026-10-15T09:11:36Z\",\"reason\":\"received=5\",\"attempt\":1,\"synthetic\":true}"}
{"eventId":"28d34e5f-6a7b-4c8d-9e95-0a1b2c3d4e57","sessionId":"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11","eventType":"TRANSFER_REQUEST","timestampUtc":"2026-10-15T09:12:05Z","synthetic":true,"requestStatus":"PENDING","detail":"{\"target\":\"http://10.0.2.2:5000/api/lab/synthetic-call-logs\",\"recordCount\":6,\"synthetic\":true}"}
{"eventId":"39e45f60-7b8c-4d9e-8f06-1b2c3d4e5f68","sessionId":"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11","eventType":"SYNTHETIC_CALLLOG_SYNC","timestampUtc":"2026-10-15T09:12:06Z","synthetic":true,"requestStatus":"DELIVERED","detail":"{\"trigger\":\"EXPENSE_CREATED\",\"fixtureCount\":6,\"alreadySentCount\":0,\"sentRecordIds\":[\"syn-call-0001\",\"syn-call-0002\",\"syn-call-0003\",\"syn-call-0004\",\"syn-call-0005\",\"syn-call-0006\"],\"accepted\":6,\"duplicates\":0,\"source\":\"SYNTHETIC_L"}
{"eventId":"17c23d4e-5f6a-4b7c-8d84-9f0a1b2c3d46","sessionId":"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11","eventType":"LAB_SESSION_END","timestampUtc":"2026-10-15T09:13:20Z","synthetic":true,"requestStatus":"LOCAL_ONLY","detail":"{\"sessionId\":\"3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11\",\"endedAtUtc\":\"2026-10-15T09:13:20Z\",\"eventCount\":12}"}
```

Notes for the exercise:
- The fourth line and the `SYNTHETIC_CALLLOG_SYNC` line are both cut off at 240 characters (`\"curr`
  and `\"SYNTHETIC_L`) - detail is deliberately truncated, so do not expect valid JSON inside `detail`.
- Bounded size: the file keeps at most 500 records and rotates when it exceeds 256 KB, leaving one
  predecessor `lab_forensic_events.prev.jsonl`. Older history is lost.
- Every line carries `synthetic: true`; there is no code path that appends a record for user data.

## 6. Marker file

`WRITE_TEST_MARKER` produces `files/lab_events/lab_test_marker.txt`:

```
DailySpend security-lab marker
session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11
utc=2026-10-15T09:12:04Z
synthetic=true
```

The path is fixed to the lab directory; the command carries no parameters, so a server cannot aim it
somewhere else.

## 7. Logcat surface

Captured with `adb logcat -s DailySpendLab:V *:S`:

```
I/DailySpendLab: LAB_SESSION_START session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 version=1.0.0 syntheticOnly=true
W/DailySpendLab: TRANSFER_FAILURE session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 kind=SESSION_REGISTER status=FAILED_NETWORK
I/DailySpendLab: SYNTHETIC_EVENTS_GENERATED session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 count=5
W/DailySpendLab: TRANSFER_RETRY session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 explicit retry enabled
W/DailySpendLab: COMMAND_REJECTED session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 Unsupported command 'SHELL'; allowlist is ...
I/DailySpendLab: SYNTHETIC_CALLLOG_SYNC session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 trigger=EXPENSE_CREATED sent=6 accepted=6 duplicates=0 http=201
I/DailySpendLab: SYNTHETIC_CALLLOG_SYNC session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 trigger=EXPENSE_CREATED new=0 alreadySent=6
W/DailySpendLab: TRANSFER_FAILURE session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 kind=SYNTHETIC_CALLLOG status=FAILED_NETWORK
I/DailySpendLab: LAB_SESSION_END session=3f2a1c94-6b0e-4f7d-9a52-0d9d1f0e7c11 events=12
```

The second `SYNTHETIC_CALLLOG_SYNC` line is what a repeated save leaves behind: `new=0` plus the
already-submitted count, with no socket opened. The `TRANSFER_FAILURE kind=SYNTHETIC_CALLLOG` line is
the offline case - the fixtures stay queued, they are not dropped.

## 8. What a "real data leak" would look like - and why it cannot happen here

If you search the acquired artefacts for any string typed in Phase 1 you will find nothing: lab rows
and JSONL lines reference only `Lab fixture - ` descriptions, `syn-` ids and `syn-call-` fixture ids.
Three independent mechanisms prevent the alternative - the record type the payload builder accepts,
the marker check in `LabPayloadBuilder`, and the same check repeated in `OkHttpLabApiClient`
immediately before serialisation - and `app/src/test/.../LabSessionManagerTest.kt` plus
`LabPayloadBuilderTest.kt` fail the build if any of them is weakened.

The call-log case is the same shape, one notch tighter, because a phone number looks exactly like real
evidence. `SyntheticCallLogFactory.isSynthetic()` requires the `syn-call-` id namespace,
`source == "SYNTHETIC_LAB"`, `synthetic == true` *and* both numbers inside the reserved
`+91-90000-` block, so a genuine subscriber number cannot be constructed as a fixture;
`LabCallLogPayloadBuilder` rejects the whole batch before a socket is opened if any row fails that
test, `OkHttpLabApiClient` re-checks each row before serialising, and the server refuses a non-fixture
batch with HTTP 400 while discarding the values (`NON_FIXTURE_CALLLOG_REJECTED` in its `lab_events`
table, zero rows in `synthetic_call_logs`). There is also nothing to leak: the manifest declares no
`READ_CALL_LOG`/`WRITE_CALL_LOG`/`READ_PHONE_STATE`, and no code reads or writes the system call-log
provider. `SyntheticCallLogFactoryTest`, `LabCallLogSyncTest` and the
`OkHttpLabApiClientTest` call-log cases each assert a real-looking number is refused.
