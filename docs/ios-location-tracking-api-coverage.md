# API coverage and acceptance map

The 8.1.0 interface has **47 distinct methods and 15 listener events**. Each row is a future acceptance requirement, not a passing test. Target outcomes below are **proposed**, not implemented support. “Supported” means required before S4, “limited” means an explicit iOS constraint, and “unavailable” means no implementation claim. Existing methods retain documented result shapes except named additions in RFC §4.3/§4.4. Read the [contracts](ios-location-tracking-contracts.md), [RFC §4](ios-location-tracking-rfc.md#4-architecture-and-interface-map) and [validation groups](ios-location-tracking-validation.md).

## Existing method coverage

| Method | Contract | Tests | Stage | Acceptance oracle |
|---|---|---|---|---|
| `ready` | C02 C18 | T01 T02 T09 | S1 S2 | Supported guarded initialization: State after atomic validation; invalid input INVALID_ARGUMENT, absent guard SESSION_REQUIRED. Never creates/renews activation or extends deadline. |
| `setConfig` | C02 C18 | T01 T02 T09 | S1 S2 | Supported: State after nested merge with omitted/null semantics. Actual wrapper is {config,session?}; identical auth can repeat, replacement rejected; authorization:null is guarded end alias. |
| `reset` | C02 C18 | T01 T02 T09 | S1 S2 | Supported guarded config reset: final-merged validation cannot extend/rebind grant/cutoff or rewind epoch. Explicit null-auth/reset-to-defaults end alias revokes grant and increments epoch; replacement credentials require protocol renewal. |
| `getState` | C02 C18 | T01 T02 T09 | S1 S2 | Supported: masked config and actual intent/cleanup/capability. Unbound returns safe off/empty state; bound context requires SessionGuard; no tokens, A cached fix or binding secret. |
| `start` | C04 C05 C12 | T02 T03 T10 | S1 S2 S3 | Supported guarded route intent: State after durable start audit; repeated same-grant start is idempotent. SESSION_REQUIRED/MISMATCH or expired/delivery-only grant rejects; stop/cutoff wins races. |
| `stop` | C04 C05 C12 | T02 T03 T10 | S1 S2 S3 | Supported guarded State after persisted off and subscription cancellation. Queue can replay within delivery lease; stop does not purge or extend grant. No late capture; stale A stop cannot affect B. |
| `changePace` | C04 C05 C12 | T02 T03 T10 | S1 S2 S3 | Supported: Promise<void>, exact motion transition/anchor audit. Stale guard rejects SESSION_MISMATCH; cannot restart a stopped/expired route or extend cutoff. |
| `startGeofences` | C04 C20 | T02 T12 | S4 | Limited guarded mode: State only with valid grant and actual registrations. ACCURACY_REDUCED/PERMISSION_DENIED/LOCATION_DISABLED/capacity failure explicit; no wake claim from desired-only definitions. |
| `getCurrentPosition` | C06 C21 | T01 T03 T02 | S1 S4 | Supported foreground: Location from best sample within maxAge/timeout; invalid args INVALID_ARGUMENT, denied PERMISSION_DENIED, expiry TIMEOUT. Explicit persist:false works unbound and creates no shared cache/queue. |
| `watchPosition` | C06 C21 | T01 T03 T02 | S1 S4 | Supported guarded foreground watch: CallbackID, Location or documented error, timeout/maxAge/persist behavior. Clear/stop/session-end cancels scoped handles; no post-barrier callbacks. |
| `clearWatch` | C06 C21 | T01 T03 T02 | S1 S4 | Supported: void and exactly one handle release; repeated clear is safe. No subsequent callback; stale guard cannot cancel a newer watch. |
| `getOdometer` | C05 C06 C21 | T03 T07 | S2 | Supported: {odometer:number}; distance thresholds and persisted value follow goldens. Empty-session value=0, unavailable storage is an error. |
| `setOdometer` | C05 C06 C21 | T03 T07 | S2 | Supported: {odometer:number}; finite/nonnegative validation INVALID_ARGUMENT before mutation; persisted value read back after reopen. |
| `resetOdometer` | C05 C06 C21 | T03 T07 | S2 | Supported: {odometer:0} only after persisted reset, serialized with sample processing. Old callback cannot add A distance into B. |
| `getLocations` | C14 C18 C21 | T01 T04 T07 T09 | S1 S2 | Supported: {locations:Location[]} immutable queued records within requested limit; IO_ERROR is distinct from empty. No A record exposed under B or without guard. |
| `getCount` | C14 C18 C21 | T01 T04 T07 T09 | S1 S2 | Supported: {count:number} from real current queue. Empty=0, inaccessible storage=IO_ERROR, never fake zero. |
| `insertLocation` | C14 C18 C21 | T01 T04 T07 T09 | S1 S2 | Supported: {uuid:string} only after real SQLite commit. Accuracy<=0 INVALID_ARGUMENT, store failure IO_ERROR. Does not reset heartbeat or prove active route. |
| `destroyLocation` | C14 C18 C21 | T01 T04 T07 T09 | S1 S2 | Supported: {deleted:boolean}; unknown UUID=false. Delete only the current session row and retire its own attempt; stale completion cannot reinsert/delete another row. |
| `destroyLocations` | C14 C18 C21 | T01 T04 T07 T09 | S1 S2 | Supported: {count:number} of actual deletions, not submitted count. Stop task admission for deleted rows and fence late acknowledgements; disk error rejects IO_ERROR. |
| `sync` | C15 C16 C17 C18 | T04 T06 T08 T09 T15 | S1 S2 | Supported guarded: {locations:Location[]} only for current acknowledged attempt deletions; retain failures. Frozen context, no redirects/ambient credentials; logical attempts distinct from exchanges; row+state readback required. |
| `addGeofence` | C20 | T01 T12 | S4 | Supported with limits: void only after registration. Invalid geometry INVALID_ARGUMENT, overflow TOO_MANY_GEOFENCES, reduced precision ACCURACY_REDUCED; no desired/active mutation on prevalidation refusal. |
| `addGeofences` | C20 | T01 T12 | S4 | Supported with limits: void only if all register. Prevalidate whole batch; OS partial failure rolls back new registrations, retains prior desired set and reports per-item diagnostics; failed rollback blocks reconciliation. |
| `removeGeofence` | C20 | T01 T12 | S4 | Supported: void after owned condition removal and desired-state commit. Missing owned ID is idempotent; foreign conditions are preserved; failure not reported as success. |
| `removeGeofences` | C20 | T01 T12 | S4 | Supported: omitted/null removes all owned desired/active; [] removes none. Each removal is reconciled; no foreign deletion; actual counts remain truthful. |
| `getGeofences` | C20 | T01 T12 | S4 | Supported: {geofences:Geofence[]} desired definitions; separate State.geofenceCapability reports active. No fake registration from nonempty desired list. |
| `getGeofence` | C20 | T01 T12 | S4 | Supported: {geofence:Geofence|null} from desired set. Missing=null; guard blocks old extras/coordinates from another session. |
| `geofenceExists` | C20 | T01 T12 | S4 | Supported: {exists:boolean} from current desired set; false for absent identifier. It does not imply an active OS registration. |
| `getHeartbeatStatus` | C08 C09 | T05 | S3 | Limited: actual iOS strategy, nullable Android fields and estimated/null next_at. Suspend creates no synthetic missed events; no next_at deadline promise. |
| `getProviderState` | C10 C11 | T01 T11 T15 | S1 S3 S5 | Supported pre-ready/context-free: actual services/auth/accuracy, corelocation, gps/network=null. Region unavailable under reduced accuracy; unsupported BE cannot turn it into accurate server status. |
| `checkPermissions` | C10 C11 | T01 T11 T15 | S1 S3 S5 | Supported pre-ready/context-free: PermissionStatus with raw iOS authorization plus existing projection. A denied/restricted status is data, not invented grant; no prompt. |
| `requestPermissions` | C10 C11 | T01 T11 T15 | S1 S3 S5 | Supported pre-ready/context-free, foreground only: PermissionStatus. Re-read actual grants after staged prompts; refusal remains denied/prompt as observed; no background dialog or loops. |
| `isPowerSaveMode` | C09 C23 | T01 T11 | S3 S4 | Supported: {isPowerSaveMode:boolean} from actual Low Power Mode; no Android Doze inference. |
| `getBatteryOptimizationStatus` | C09 C23 | T01 T11 | S3 S4 | Limited: typed unavailable/null Android exemption fields; never fabricate exemption from Low Power Mode. Exact nullable type changes must accompany RFC §4.4. |
| `getPowerManagerInfo` | C09 C23 | T01 T11 | S3 S4 | Limited: explicit iOS/unavailable Android power-manager fields, no guessed manufacturer exemption or private settings destination. |
| `getDeviceInfo` | C22 C23 | T01 T11 T13 | S3 S4 | Supported pre-ready/context-free: actual platform=ios/model/OS, sdkInt=null and unavailable Android fields. IDFV is read separately by the native record factory; no new DeviceInfo identifier field. |
| `getSensors` | C22 C23 | T01 T11 T13 | S3 S4 | Supported pre-ready/context-free: real availability, with unknown/unavailable distinct. No motion denial fabricated as missing hardware. |
| `openBatteryOptimizationSettings` | C23 | T01 T11 | S4 | Unavailable Android screen: resolves {opened:false}. No hidden iOS Settings deep link. |
| `openPowerManagerSettings` | C23 | T01 T11 | S4 | Unavailable Android screen: resolves {opened:false}; does not open an unrelated panel. |
| `openLocationSettings` | C10 C23 | T01 T11 | S4 | Limited foreground: public app-settings route with {opened:boolean}, no deep link directly to an unsupported system panel. |
| `openAppSettings` | C10 C23 | T01 T11 | S4 | Limited foreground: {opened:boolean} via public app-settings URL only. Failure=false; no fabricated Settings success. |
| `log` | C22 C26 | T01 T07 T13 | S4 | Supported: void after bounded/redacted append; invalid level INVALID_ARGUMENT, storage failure IO_ERROR. Guarded when bound, no arbitrary A-secret injection into B export. |
| `getLog` | C22 C26 | T01 T07 T13 | S4 | Supported: {log:string} from bounded redacted current-session log. Pre-ready unbound returns only safe empty/system diagnostics; old coordinates/headers cannot survive barrier. |
| `destroyLog` | C22 C26 | T01 T07 T13 | S4 | Supported: void after actual bounded log deletion. IO_ERROR leaves explicit failure; a stale A cleanup cannot erase B logs. |
| `uploadLog` | C22 C26 | T01 T07 T13 | S4 | Limited foreground explicit action: {success:boolean,status:number} from actual selected transport; same no-redirect/no-ambient policy. Redacted payload only; HTTP status is not proof a person read it. |
| `emailLog` | C22 C26 | T01 T07 T13 | S4 | Limited: void after foreground system composer handoff; unavailable mail capability rejects UNAVAILABLE. User cancel is not proof of remote delivery. Redacted attachment only. |
| `addListener` | C03 C22 | T01 T02 | S1 S4 | Supported through §4.8 plugin-owned wrapper, not Capacitor built-in proxy. Frozen guard reaches new native subscribe options; resolve handle only after ready+callbackId. remove awaits exact guarded unsubscribe/release; old/raw iOS addListener refuses and never receives notifyListeners payloads. |
| `removeAllListeners` | C03 C22 | T01 T02 | S1 S4 | Supported: void, release all current handles exactly once. Native engine stays alive; no callback payload from a retired guard. |

## Existing event coverage

| Event | Contract | Tests | Stage | Acceptance oracle |
|---|---|---|---|---|
| `location` | C06 C07 C21 | T03 T04 | S1 S2 | Accepted normalized record and real persistence result. |
| `motionchange` | C05 C07 | T02 T03 | S2 | Boundary fixtures establish transition and anchor time. |
| `activitychange` | C05 | T03 T11 | S2 S3 | Explicit confidence mapping and denial fallback. |
| `providerchange` | C10 C11 | T11 T15 | S3 S5 | Observable permission/services/accuracy changes and paired server status. |
| `heartbeat` | C08 C09 | T05 | S3 | At most one due record, correct strategy, old fix time and current creation time. |
| `geofence` | C20 | T12 | S4 | Observed ENTER/EXIT/DWELL semantics, lateness and unknown-state distinction. |
| `geofenceschange` | C20 | T12 | S4 | Desired versus actual registration changes and errors. |
| `http` | C15 C16 | T04 T06 T09 | S2 S3 | Actual request response, no stale success after barrier. |
| `connectivitychange` | C16 C23 | T06 T11 | S2 S3 | Actual path changes, no inference of server reachability. |
| `powersavechange` | C09 C23 | T11 | S3 | Real Low Power Mode change. |
| `enabledchange` | C04 C12 | T02 T10 | S1 S3 | Intent and reason match actual durable transition, no invented death time. |
| `notificationaction` | C23 | T01 T11 | S4 | Subscription accepted; no Android notification event fabricated on iOS. |
| `authorization` | C17 C22 | T08 T09 T13 | S2 | Credential refresh result belongs to current operation; exports redact secrets. |
| `locationerror` | C06 C21 | T02 T03 T11 | S1 S3 | Source registration/sample failure settles callers and releases handles. |
| `storagefull` | C14 C22 | T07 T13 | S2 | Real store failure, UUID/context and no false durable receipt. |

## Proposed addition, not part of the baseline count

| Method | Contract | Tests | Stage | Acceptance oracle |
|---|---|---|---|---|
| `requestTemporaryFullAccuracy` | C10 C11 | T01 T11 T14 | S3 S5 | Foreground, valid purpose key, real completion and re-read accuracy; grant/decline/expiry/error; Android/web explicit UNIMPLEMENTED. |

Four additional proposed protocol methods are absent from 8.1.0. Android/web reject these with UNIMPLEMENTED; their existing paths remain unchanged.

| Method | Contract | Tests | Stage | Acceptance oracle |
|---|---|---|---|---|
| `getTrackingCapabilities` | C02 C18 C25 | T01 T14 | S1 S5 | Pre-ready, safe protocol/epoch/transport receipt; never grants activation. Exact old shell absence leaves new web off. |
| `activateTrackingSession` | C02 C04 C18 C19 C25 | T01 T02 T09 T10 T14 | S1 S2 S5 | Foreground expectedEpoch CAS, binding/grant validation and prepared SQLite/Keychain commit. Return SessionReceipt off. Stale/missing grant, wrong binding or cleanup rejects without partial activation. |
| `updateTrackingCredentials` | C17 C18 | T01 T08 T09 | S2 S5 | Same binding/guard, exact revision+1, retained queue/cutoff. Delayed A refresh and storage failure cannot mutate B or use fallback plaintext. |
| `endTrackingSession` | C04 C18 C26 | T01 T02 T09 T13 T14 | S2 S5 | Tombstone/off/epoch before await, cancel and purge context, receipt only after quiescence. Crash/failure stays pending; stale/repeated end is safe and cannot clear B. |
| `subscribe` | C03 C18 C22 C25 | T01 T02 T09 T14 | S1 S4 S5 | New callback-type bridge options carry frozen SessionGuard/event/subscriptionId; native validates before saved-call registration, then ready/event/closed envelope. Raw iOS addListener is refused; no sensitive notifyListeners stream. |
| `unsubscribe` | C03 C18 C22 | T01 T02 T09 T14 | S1 S4 S5 | Exact guard/event/subscriptionId/callbackId registry tuple; Promise<void> after native release. Duplicate/retired A removal is safe; delayed A cannot remove B. Wrapper gates locally before await. |

## Admission, payload and lifecycle rules

These rules are normative for every mapped row. Exact baseline result/payload types come from pinned definitions.ts ([P3](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/src/definitions.ts#L734)); RFC §4 names additions. S0 freezes per-field JSON goldens and error payloads before coding. Passing only a group name or counting method strings cannot establish support.

- Default pre-ready refusal is NOT_READY. The 13 baseline exceptions are listed in C02. The iOS session protocol adds stricter admission even after ready: every context-owned call/listener carries SessionGuard. SESSION_REQUIRED, SESSION_MISMATCH and SESSION_CLEANUP_PENDING have the exact no-mutation/pending behavior in RFC §4.3. Pending cleanup admits only safe diagnostics, settings/permissions and idempotent cleanup; it cannot admit new context state.
- Context-free permission, provider, device, sensor and settings diagnostics remain callable pre-ready. Unbound getState/getLog expose only safe off/empty system state. Unbound getCurrentPosition is foreground persist:false only. A watch carries a guard in its callback-method options. Listeners use the §4.8 wrapper and named guarded native subscribe/unsubscribe; built-in addListener cannot carry the third argument. A stale guard cannot become the current session at delayed registration. Failed registration must release handles; removal never destroys the core.
- Keep result shapes from definitions.ts; reject with the documented code and a redacted message. Geofence partial OS failure rolls back and emits per-item diagnostics; failed reconciliation remains blocked. Unsupported Android power screens return opened:false; notificationaction accepts a subscription but emits no fabricated event. Android-only diagnostic fields need explicit nullable/unavailable types, not invented values.
- For location, motion, provider, heartbeat, geofence, HTTP, authorization and storage events, assert every payload field, timestamp, UUID, order and store outcome. Events follow serial committed transitions; an acquisition event is not a durable receipt if SQLite fails. A stale epoch can retire bookkeeping but emits no current-session event. T01 creates one positive and one failing fixture per exported method/event; C01–C26 controls below supply the minimum adverse case, not an exhaustive implementation suite.

## Contract fixture crosswalk

Stable design fixture IDs below become concrete shared/Swift/HTTP fixtures after approval. Each per-method/event receipt names its Cnn-P/Cnn-N fixtures plus the relevant Tnn/Bnn/Dnn receipt. The negative fixture must make the oracle fail, not merely return a green mocked error.

| Contract | Positive fixture | Failing/adverse fixture and test group |
|---|---|---|
| C01 | C01-P fresh tarball host discovers every native method/resource | C01-N remove registration/privacy resource; package check fails (T16). |
| C02 | C02-P valid guarded ready and omitted/null merge | C02-N stale/missing guard and invalid config; no mutation/authority (T01/T14). |
| C03 | C03-P no-scene native restore and ordered listeners | C03-N actual Capacitor proxy drops guard, delayed A subscribe/remove and raw notifyListeners bypass; fail saved-call/delivery/order counts (T02/T04). |
| C04 | C04-P one committed start/stop audit | C04-N delayed post-stop sample/reordered start; capture assertion fails (T02). |
| C05 | C05-P displacement/confidence threshold traces | C05-N uncertain fix falsely causes motion; fixed stream differs (T03). |
| C06 | C06-P valid/stale/invalid sample filter goldens | C06-N negative accuracy/callback-time substitution accepted; stream fails (T03/B04). |
| C07 | C07-P separate capture/creation/attempt/receipt times | C07-N wall-minus-uptime boot guess or changed timestamp on retry; time fixture fails (T05/T10). |
| C08 | C08-P one due heartbeat during permitted execution | C08-N synthetic suspended backfill or early timer; record count fails (T05). |
| C09 | C09-P actual strategy and null Android fields | C09-N fake exact/exemption or nonnull SLA; payload comparison fails (T05/T11). |
| C10 | C10-P actual staged grant and accuracy snapshots | C10-N nil-error prompt treated as precise; capability assertion fails (T11). |
| C11 | C11-P seeded Android→iOS provider/status transition | C11-N omit snapshot on old BE; status/enablement assertion fails (B06/T15). |
| C12 | C12-P valid headless grant restores only under approved flags | C12-N expired/off/stopOnTerminate:true restore; no-capture assertion fails (T02/T10). |
| C13 | C13-P distinct observed termination methods/times | C13-N infer user force-quit from willTerminate; evidence claim fails (D09/D10). |
| C14 | C14-P real SQLite reopen/protection/migration | C14-N disk-full false durable receipt or WAL backup; readback/attribute assertion fails (T07). |
| C15 | C15-P same UUID lost-ack retry and exact state | C15-N new retry UUID/duplicate acceptance/delete-before-ack; DB join fails (T04/B01/B05). |
| C16 | C16-P real ephemeral retry/no-redirect request | C16-N redirect-to-200 or ambient cookie accepted; destination/queue oracle fails (T06). |
| C17 | C17-P same-login credential revision preserves rows | C17-N stale A refresh writes B secret; credential alias assertion fails (T08/T09). |
| C18 | C18-P complete end receipt before B activation | C18-N any failed cleanup treated complete or A read callback reaches B; canary assertion fails (T09). |
| C19 | C19-P Berlin/Cairo UTC and elapsed deadline goldens | C19-N local-hour fold skip/mode extension/unknown-clock resume; cutoff assertion fails (T10). |
| C20 | C20-P desired/active native budget and restored precision | C20-N fake active reduced-accuracy region, foreign deletion or failed rollback hidden; registration counts fail (T12). |
| C21 | C21-P position/watch timeout/persist and real queue methods | C21-N callback after clear or persist:false queues; handle/store assertion fails (T01/T03/T09). |
| C22 | C22-P native IDFV and bounded redacted diagnostics | C22-N cached nil IDFV/invented ID or token in errors; identity/canary assertion fails (T11/T13). |
| C23 | C23-P truthful unavailable settings/power behavior | C23-N fake Android screen/event on iOS; result/event assertion fails (T01/T11). |
| C24 | C24-P Bricks-envelope real accepted rows and exact snapshots | C24-N unrecognized envelope/200 no storage/conflict/patch failure; join/state assertions fail (B01–B07). |
| C25 | C25-P approved native+web+BE grant only | C25-N actual old bundle auto-start, BE rollback or mismatched grant; activation assertion fails (T14/B06). |
| C26 | C26-P authorized recipients and local cleanup/disclosures | C26-N direct-ID/tenant/scope leak, Keychain orphan or crash canary; real HTTP/local oracle fails (B08/T09/T13). |

Only the 47/15 baseline rows count toward the baseline inventory. There are **seven proposed new methods** (five feature/session methods plus two low-level native subscription methods), plus explicit type/option/error additions; they require a semver and exhaustive-consumer review. No support claim is allowed before its mapped stage and per-row receipts complete.
