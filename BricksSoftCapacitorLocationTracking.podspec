require 'json'
package = JSON.parse(File.read(File.join(__dir__, 'package.json')))
Pod::Spec.new do |s|
  s.name = 'BricksSoftCapacitorLocationTracking'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['homepage']
  s.author = package['author']
  s.source = { :git => 'https://github.com/bricks-soft/location-tracking.git', :tag => s.version.to_s }
  s.source_files = 'ios/Sources/LocationTrackingPlugin/**/*.swift'
  s.resource_bundles = { 'LocationTrackingPrivacy' => ['ios/Sources/LocationTrackingPlugin/PrivacyInfo.xcprivacy'] }
  s.ios.deployment_target = '15.0'
  s.module_name = 'LocationTrackingPlugin'
  s.swift_version = '5.9'
  s.dependency 'Capacitor', '>= 8.0.0', '< 9.0'
  s.libraries = 'sqlite3'
end
