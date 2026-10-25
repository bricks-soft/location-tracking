# iOS location tracking sources

Inspected plugin: master at `f1edfd6279965f581d46d7a3434fa93e3d515a2f`, tag and package 8.1.0, on 25 October 2026. New transport/accuracy/error evidence was read on 26 October 2026 (Cairo). Code links pin source; mutable Apple metadata is not an installed SDK or runtime result. Refresh the source baseline before implementation.

The package has no iOS implementation at this pin. Current Android source and round-3 decisions take precedence over older proposals. The RFC is proposed behavior, including all new methods and receiving-service dependencies; none of those APIs is claimed to exist today.

| ID | Public primary source |
|---|---|
| P1 | [Plugin package and iOS exclusion](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/package.json#L1) |
| P2 | [No iOS sources](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/ios/README.md#L1) |
| P3 | [Current JS types and methods](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/src/definitions.ts#L1) |
| P4 | [Round 3 owner decisions; earlier rules superseded](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/docs/DECISIONS.md#L711) |
| P5 | [Android composition root](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/main/java/com/brickssoft/locationtracking/core/Components.kt#L72) |
| P6 | [Motion contract](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/main/java/com/brickssoft/locationtracking/engine/MotionStateMachine.kt#L62) |
| P7 | [Quality filter order](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/main/java/com/brickssoft/locationtracking/processing/DefaultLocationProcessor.kt#L10) |
| P8 | [Queue, ordering and pruning](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/main/java/com/brickssoft/locationtracking/data/SqliteLocationStore.kt#L18) |
| P9 | [Retries, priorities, single flight, credential changes](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/main/java/com/brickssoft/locationtracking/http/OkHttpSyncer.kt#L46) |
| P10 | [Native JWT refresh](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/main/java/com/brickssoft/locationtracking/http/AuthorizationManager.kt#L1) |
| P11 | [Persistence and listener order, including failed inserts](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/main/java/com/brickssoft/locationtracking/record/RecordSink.kt#L25) |
| P12 | [Ownerless queue and logout sequence](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/README.md#L650) |
| P13 | [iOS outlook, proposal rather than implementation](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/docs/heartbeat.md#L452) |
| P14 | [Real components with fake hardware and process restart](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/test/java/com/brickssoft/locationtracking/integration/FullStackHarness.kt#L87) |
| P15 | [Reusable Node fault server](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/testing/e2e-kit/src/backoffice.ts#L1) |
| P16 | [Companion native API](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/docs/native-api.md#L1) |
| P17 | [Round 2 overrides and scenario contracts](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/docs/e2e/architecture.md#L1) |
| P18 | [Wire guide, partly stale against round 3 source](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/docs/wire-format.md#L1) |
| P19 | [Manual CI only](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/.github/workflows/ci.yml#L1) |
| P20 | [Independent expected defaults](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/test/web-config.test.mjs#L1) |
| A1 | [Background lifecycle](https://developer.apple.com/documentation/corelocation/handling-location-updates-in-the-background) |
| A2 | [Standard manager service](https://developer.apple.com/documentation/corelocation/cllocationmanager/startupdatinglocation()) |
| A3 | [Significant changes, power and relaunch limits](https://developer.apple.com/documentation/corelocation/cllocationmanager/startmonitoringsignificantlocationchanges()) |
| A4 | [Staged Always authorization](https://developer.apple.com/documentation/corelocation/cllocationmanager/requestalwaysauthorization()) |
| A5 | [Reduced accuracy](https://developer.apple.com/documentation/corelocation/cllocationmanager/accuracyauthorization) |
| A6 | [Automatic pause](https://developer.apple.com/documentation/corelocation/cllocationmanager/pauseslocationupdatesautomatically) |
| A7 | [Legacy region limit and iOS 27.2 deprecation metadata](https://developer.apple.com/documentation/corelocation/cllocationmanager/startmonitoring(for:)) |
| A8 | [Background transfers, including force-quit cancellation](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/background(withidentifier:)) |
| A9 | [BackgroundActivitySession, iOS 17](https://developer.apple.com/documentation/corelocation/clbackgroundactivitysession-4nl4y) |
| A10 | [ServiceSession, iOS 18](https://developer.apple.com/documentation/corelocation/clservicesession-pt7n) |
| A11 | [Temporary precise access](https://developer.apple.com/documentation/corelocation/cllocationmanager/requesttemporaryfullaccuracyauthorization(withpurposekey:completion:)) |
| A12 | [App Review 2.5.4, 5.1.1 and 5.1.5](https://developer.apple.com/app-store/review/guidelines/) |
| A13 | [Data collection, linked data and tracking definitions](https://developer.apple.com/app-store/app-privacy-details/) |
| A14 | [Manifest declarations](https://developer.apple.com/documentation/bundleresources/describing-data-use-in-privacy-manifests) |
| A16 | [Deprecated launch key and replacement guidance](https://developer.apple.com/documentation/uikit/uiapplication/launchoptionskey/location) |
| A17 | [Motion availability](https://developer.apple.com/documentation/coremotion/cmmotionactivitymanager) |
| P21 | [Pre-ready allowlist](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/android/src/main/java/com/brickssoft/locationtracking/bridge/PluginHandlers.kt#L531) |
| A18 | [CLMonitor Swift actor, iOS 17.0+](https://developer.apple.com/documentation/corelocation/clmonitor-2r51v) |
| A19 | [20 conditions of any type; event iteration and relaunch](https://developer.apple.com/documentation/corelocation/monitoring-the-user-s-proximity-to-geographic-regions) |
| A20 | [Continuous background execution and location capability](https://developer.apple.com/documentation/corelocation/cllocationmanager/allowsbackgroundlocationupdates) |
| A21 | [Termination callback limits, no cause discriminator](https://developer.apple.com/documentation/uikit/uiapplicationdelegate/applicationwillterminate(_:)) |
| A22 | [Asynchronous task cancellation](https://developer.apple.com/documentation/foundation/urlsessiontask/cancel()) |
| A23 | [Application launch before scene connection](https://developer.apple.com/documentation/uikit/uiapplicationdelegate/application(_:didfinishlaunchingwithoptions:)) |
| A24 | [Terminal task callback and HTTP status distinction](https://developer.apple.com/documentation/foundation/urlsessiontaskdelegate/urlsession(_:task:didcompletewitherror:)) |
| A25 | [Task enumeration for reconciliation](https://developer.apple.com/documentation/foundation/urlsession/getalltasks(completionhandler:)) |
| A26 | [File protection after first unlock](https://developer.apple.com/documentation/foundation/fileprotectiontype/completeuntilfirstuserauthentication) |
| A27 | [Non-migrating after-first-unlock Keychain class](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlockthisdeviceonly) |
| A28 | [Native IDFV availability and lifetime](https://developer.apple.com/documentation/uikit/uidevice/identifierforvendor) |
| A29 | [Observable authorization enum](https://developer.apple.com/documentation/corelocation/clauthorizationstatus) |
| A30 | [Monitor add and initial unknown state](https://developer.apple.com/documentation/corelocation/clmonitor-2r51v/add(_:identifier:)) |
| A31 | [Retained asynchronous monitor events](https://developer.apple.com/documentation/corelocation/clmonitor-2r51v/events-swift.property) |
| A32 | [Circular geographic condition availability](https://developer.apple.com/documentation/corelocation/clmonitor-2r51v/circulargeographiccondition) |
| K1 | [Capacitor 8 deployment and Xcode floors](https://capacitorjs.com/docs/updating/8-0#ios) |
| K2 | [Moment Timezone gap and overlap parsing](https://momentjs.com/timezone/docs/#/using-timezones/parsing-ambiguous-inputs/) |
| A33 | [Redirect interception only in default/ephemeral sessions](https://developer.apple.com/documentation/foundation/urlsessiontaskdelegate/urlsession(_:task:willperformhttpredirection:newrequest:completionhandler:)) |
| A34 | [Permitted-execution default sessions versus background daemon restrictions](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background) |
| A35 | [Temporary inability to get a fix](https://developer.apple.com/documentation/corelocation/clerror-swift.struct/code/locationunknown) |
| A36 | [Denied location authorization error](https://developer.apple.com/documentation/corelocation/clerror-swift.struct/code/denied) |
| P22 | [Existing raw Capacitor proxy export](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/src/plugin.ts#L1) |
| P23 | [Standalone typed event helpers](https://github.com/bricks-soft/location-tracking/blob/f1edfd6279965f581d46d7a3434fa93e3d515a2f/src/events.ts#L55) |
| K3 | [Capacitor 8.5.2 native proxy argument forwarding](https://github.com/ionic-team/capacitor/blob/8.5.2/core/src/runtime.ts) |
| K4 | [Published Capacitor 8.5.2 runtime bytes](https://unpkg.com/@capacitor/core@8.5.2/dist/index.js) |

CLMonitor and its Swift circle/add/events APIs start at iOS 17.0. The app-wide limit is 20 conditions of any type. CLServiceSession starts at iOS 18. The legacy CLLocationManager region entry point's current metadata deprecates it at iOS 27.2; this does not prove any installed SDK. Reduced accuracy makes region monitoring unavailable under the selected contract. Temporary no-fix and denied authorization are different conditions.

A33 permits redirect interception only in default/ephemeral sessions. A34 explains permitted-execution default sessions and background daemon restrictions. These sources support the initial ephemeral transport and deferred-daemon decision; they do not establish delivery on hardware. Cancel returns before callback completion. Prompt completion without an error does not prove a precision grant. Authorization enums reveal neither provisional Always nor Allow Once as distinct states.

Host/server obligations are self-contained proposed requirements in the RFC, contracts, API map and validation document. They are not claims that the receiving API already supports sequence, tie, clock or reader policy changes. Qualified receiving support and explicit deployment order remain enabling dependencies. Source review cannot replace real authenticated HTTP, stored-state and Release evidence.
