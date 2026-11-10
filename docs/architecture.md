# Architecture

DailySpend is a single-module Android app split by package into four cooperating areas: the
everyday tracker (UI + domain + data), the dependency graph, the opt-in security lab, and the
network client for the lab server. The lab and the network client have no path into the expense
table, and the expense table has no path into the network client.

## 1. Layering

```mermaid
graph TD
    subgraph UI layer
        DASH[DashboardScreen]
        EDIT[ExpenseEditScreen]
        HIST[HistoryScreen]
        SET[SettingsScreen]
        NAV[DailySpendApp navigation]
    end

    subgraph Presentation state
        DVM[DashboardViewModel]
        EVM[ExpenseEditViewModel]
        HVM[HistoryViewModel]
        SVM[SettingsViewModel]
    end

    subgraph Domain
        VAL[ExpenseValidator]
        AGG[ExpenseAggregator]
        MONEY[Money and DateUtils]
    end

    subgraph Data layer
        EREPO[ExpenseRepository]
        LREPO[SecurityLabRepository]
        EDAO[ExpenseDao]
        LDAO[SecurityLabEventDao]
        DB[(Room daily_spend.db)]
    end

    subgraph Security lab module opt-in
        MGR[LabSessionManager]
        SYN[SyntheticExpenseFactory]
        SYNCL[SyntheticCallLogFactory]
        PROC[ProcessedRecordStore dedupe ids]
        BLD[LabPayloadBuilder]
        CMD[LabCommand and LabCommandParser]
        LOG[ForensicEventLog JSONL]
        NTF[SecurityTestNotification and AndroidLabResultNotifier]
        STORE[LabSettingsStore SharedPreferences]
        CFG[LabConfig from BuildConfig]
    end

    subgraph Network
        POLICY[LabEndpointPolicy]
        CLIENT[OkHttpLabApiClient]
        SERVER[Python Flask lab server, not in this repo]
    end

    CONTAINER[AppContainer hand-written DI]

    NAV --> DASH
    NAV --> EDIT
    NAV --> HIST
    NAV --> SET
    DASH --> DVM
    EDIT --> EVM
    HIST --> HVM
    SET --> SVM

    EVM --> VAL
    DVM --> AGG
    DVM --> MONEY
    HVM --> AGG

    DVM --> EREPO
    EVM --> EREPO
    EVM --> MGR
    HVM --> EREPO
    SVM --> EREPO
    SVM --> LREPO
    SVM --> MGR

    EREPO --> EDAO
    LREPO --> LDAO
    EDAO --> DB
    LDAO --> DB

    MGR --> SYN
    MGR --> SYNCL
    MGR --> PROC
    MGR --> BLD
    MGR --> CMD
    MGR --> LOG
    MGR --> NTF
    MGR --> STORE
    MGR --> CFG
    MGR --> LREPO
    BLD --> CLIENT
    CMD --> CLIENT
    CLIENT --> POLICY
    CLIENT --> SERVER

    CONTAINER -.creates.-> DVM
    CONTAINER -.creates.-> EVM
    CONTAINER -.creates.-> HVM
    CONTAINER -.creates.-> SVM
    CONTAINER -.creates.-> EREPO
    CONTAINER -.creates.-> LREPO
    CONTAINER -.creates.-> MGR
```

The `ui/*ViewModel` classes never touch OkHttp or files directly; only `LabSessionManager` reaches
the network layer, and only `ExpenseRepository` reaches the `expenses` table.

## 2. Ordinary tracker data flow

```mermaid
sequenceDiagram
    participant U as User
    participant C as Compose screen
    participant V as ViewModel
    participant R as ExpenseRepository
    participant D as Room DAO
    participant B as SQLite

    U->>C: types amount, description, category, method, date
    C->>V: save
    V->>V: ExpenseValidator.validate
    alt validation fails
        V-->>C: field errors, nothing written
    else validation passes
        V->>R: create or update Expense
        R->>D: insert or update
        D->>B: writes expenses row, amountMinor integer
        B-->>D: row id
        D-->>R: Flow emits new list
        R-->>V: StorageResult Success
        V-->>C: exits edit screen
        C->>B: dashboard and history re-render from Flow
    end
```

Observable reads are `Flow`-based (`observeAll`, `observeInRange`, `observeRecent`, `observeCount`),
so a write anywhere in the app refreshes every subscribed screen without manual invalidation.
Failures are returned as `StorageResult.Failure(reason)` and rendered as a message bar; no database
error propagates as an exception into composition.

## 3. Lab session, happy path

```mermaid
sequenceDiagram
    participant U as User
    participant S as Settings screen
    participant M as LabSessionManager
    participant F as SyntheticExpenseFactory
    participant P as LabPayloadBuilder
    participant A as OkHttpLabApiClient
    participant H as Flask server
    participant L as Room plus JSONL

    U->>S: enable lab switch
    S->>M: updateConfig labEnabled true
    M->>L: LAB_CONFIG_UPDATED
    U->>S: Start test session
    S->>M: startSession
    M->>M: sessionId = fresh UUID, record start time
    M->>L: LAB_SESSION_START, LOCAL_ONLY
    M->>A: registerSession session_id, started_at_utc, app_version
    A->>H: POST /api/lab/sessions
    H-->>A: accepted, lab_run_id
    A-->>M: LabApiResult Success httpStatus
    M->>L: TRANSFER_RESPONSE, DELIVERED
    U->>S: Generate synthetic fixtures
    S->>M: generateSyntheticBatch
    M->>F: generate sessionStart, count
    F-->>M: eight hard-coded Lab fixture records, syn- ids
    M->>L: SYNTHETIC_EVENTS_GENERATED, LOCAL_ONLY
    U->>S: Send synthetic batch to server
    S->>M: transferSyntheticBatch
    M->>P: build sessionId, synthetic records
    P->>P: reject anything that is not a fixture
    P-->>M: SyntheticIngestRequest
    M->>L: TRANSFER_REQUEST, PENDING
    M->>A: ingestSyntheticEvents
    A->>A: re-check every description and id marker
    A->>H: POST /api/lab/synthetic-events
    H-->>A: received, status
    A-->>M: Success
    M->>L: TRANSFER_RESPONSE, DELIVERED
```

## 4. Failure and offline paths

```mermaid
flowchart TD
    START[Lab action requested] --> ENABLED{labEnabled}
    ENABLED -- false --> OFF[Disabled result, nothing sent, action refused in UI]
    ENABLED -- true --> EP{LabEndpointPolicy accepts baseUrl}
    EP -- no --> SKIP[Disabled: endpoint rejected, recorded locally]
    EP -- yes --> CALL[OkHttp call with 5s connect and read timeouts]
    CALL --> R1{outcome}
    R1 -- Success --> DEL[TRANSFER_RESPONSE DELIVERED]
    R1 -- HttpError --> FH[TRANSFER_FAILURE FAILED_HTTP]
    R1 -- SocketTimeout --> FT[TRANSFER_FAILURE FAILED_TIMEOUT]
    R1 -- IOException --> FN[TRANSFER_FAILURE FAILED_NETWORK]
    R1 -- bad JSON --> IV[TRANSFER_FAILURE INVALID_PAYLOAD]
    FN --> RETRY{user enabled Allow one retry}
    RETRY -- yes --> AGAIN[TRANSFER_RETRY then one more attempt]
    RETRY -- no --> STOP[no automatic retry, no background work]
    DEL --> LOG[Room row plus JSONL line]
    FH --> LOG
    FT --> LOG
    FN --> LOG
    IV --> LOG
    SKIP --> LOG
    OFF --> UI[Settings message bar shows the reason]
    LOG --> UI
```

Every branch produces a local record and a human-readable summary; none throws at the UI layer, and
none schedules work after the screen is closed.

## 5. Command allowlist gate

```mermaid
flowchart LR
    RESP[Server command response] --> N{command name in allowlist}
    N -- no --> RJ1[Rejected: unsupported command]
    N -- yes --> PAR{parameters empty}
    PAR -- no --> RJ2[Rejected: allowlisted actions take no arguments]
    PAR -- yes --> TS{issued_at_utc present}
    TS -- no --> RJ3[Rejected: undated command]
    TS -- yes --> SS{lab session active}
    SS -- no --> RJ4[Rejected: no session]
    SS -- yes --> EX[Local enum action only]
    EX --> A1[SHOW_LAB_STATUS]
    EX --> A2[CREATE_SYNTHETIC_EVENT]
    EX --> A3[WRITE_TEST_MARKER inside lab_events dir]
    EX --> A4[END_LAB_SESSION]
    RJ1 --> REC[LAB_COMMAND_REJECTED]
    RJ2 --> REC
    RJ3 --> REC
    RJ4 --> REC
```

The parser maps the wire string onto the `LabCommand` enum and then executes that enum value only.
Server text is never eval-ed, never passed to a shell, never used as a path outside the lab
directory, and never loaded as code.

## 6. Artefact map

| Artefact | Written by | Contents | Notes for acquisition |
| --- | --- | --- | --- |
| `databases/daily_spend.db`, table `expenses` | `ExpenseDao` | user-entered amounts (minor units), category, description, payment method, expense date, created/updated timestamps | indexed on `expenseDate` and `category`; deleted only by the user or by clearing app data |
| `databases/daily_spend.db`, table `security_lab_events` | `LabSessionManager` via `SecurityLabRepository` | session id, event type, UTC epoch millis, payload JSON, delivery status | indexed on `sessionId` and `eventType`; only the lab writes here |
| `files/lab_events/lab_forensic_events.jsonl` | `ForensicEventLog` | one JSON object per line: event id, session id, event type, ISO-8601 UTC timestamp, `synthetic` flag, request status, truncated detail | capped at 500 records and 256 KB, one rotation file `lab_forensic_events.prev.jsonl` |
| `files/lab_events/lab_test_marker.txt` | `WRITE_TEST_MARKER` allowlisted command | session id, UTC timestamp, `synthetic=true` | only ever written inside the lab directory |
| Notification shade, channel `daily_spend_security_lab` | `AndroidLabResultNotifier` | one DailySpend-owned notification per *Run Security Test* press: title, result text, fixture id, HTTP status | ephemeral and user-visible; `adb shell dumpsys notification --noredact` lists it while un-dismissed. The archived `SECURITY_TEST_NOTIFICATION` row records the same text and whether the system accepted it. No `NotificationListenerService` exists, so no other app's notifications are readable by this app |
| `shared_prefs/daily_spend_lab.xml` | `LabSettingsStoreImpl` | `lab_enabled`, `lab_base_url`, `lab_connect_timeout`, `lab_read_timeout`, `lab_retry_enabled`, `lab_max_retries` | defaults come from `BuildConfig`, so a release build has an empty base URL and lab off |
| Logcat | `AndroidLabLogger` | tag `DailySpendLab`; each line starts with the event type, then `session=<id>` and key-value detail such as `syntheticOnly=true` | ephemeral, not persisted by the app |

Both backup rule files list exactly one path, `daily_spend.db`, so the `files/lab_events` folder
never travels with an auto backup or a device transfer while the user's expense database does (see
`res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`).

## 7. Design choices worth knowing

- **Hand-written DI (`di/AppContainer.kt`)** instead of Hilt: a forensic examiner is expected to read
  every artefact the app produces, so the graph is explicit and small.
- **OkHttp without Retrofit**: five endpoints, all typed by hand, so there is no generated code and
  every request is visible in one file.
- **Sealed results everywhere** (`StorageResult`, `LabApiResult`, `CommandDecision`,
  `LabEndpointPolicy.Decision`): the UI cannot accidentally swallow an exception, and the compiler
  forces each network outcome to be handled.
- **A distinct `SyntheticExpenseRecord` type** rather than reusing `Expense`: the payload builder
  literally cannot be called with a real row.
- **Room schema is committed** to `app/schemas/.../1.json`, and destructive migration is off, so a
  database is never silently rebuilt during an exercise.
