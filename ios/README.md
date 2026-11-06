# iOS

The native iOS implementation of `@bricks-soft/capacitor-location-tracking` (iOS 15 and later; geofences on iOS 17
and later). Setup: the README, "iOS setup". Design and platform limits:
[docs/ios-location-tracking.md](../docs/ios-location-tracking.md).

- `Sources/LocationTrackingPlugin/`: the plugin, packaged for SPM (`Package.swift`) and CocoaPods
  (`BricksSoftCapacitorLocationTracking.podspec`).
- `Tests/`: XCTest unit tests. `UITests/`: permission and check-in UI tests. `HostTests/ScenarioHost.swift`: the
  test host's file channel for the shared e2e scenarios (docs/e2e-runbook.md, 6.11).

Checks: `.github/scripts/ios/check.sh all` on a Mac, or `.github/workflows/ios.yml` on GitHub (started by hand).
