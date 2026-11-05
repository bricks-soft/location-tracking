require 'xcodeproj'

root = File.join(ENV.fetch('RUNNER_TEMP'), 'ios-consumer/ios/App')
project = Xcodeproj::Project.open(File.join(root, 'App.xcodeproj'))
app = project.targets.find { |target| target.name == 'App' }
identifier = 'LTTEST0001.com.brickssoft.locationtracking.example'
entitlements = {
  'application-identifier' => identifier,
  'keychain-access-groups' => [identifier],
  'get-task-allow' => true
}
Xcodeproj::Plist.write_to_path(entitlements, File.join(root, 'App/Simulator.entitlements'))
Xcodeproj::Plist.write_to_path({ 'get-task-allow' => true, 'application-identifier' => 'LTTEST0001.com.brickssoft.locationtracking.uitests.xctrunner' }, File.join(root, 'App/SimulatorTests.entitlements'))
project.targets.each do |target|
  target.build_configurations.select { |configuration| configuration.name == 'Debug' }.each do |configuration|
    configuration.build_settings.merge!({
      'CODE_SIGN_STYLE' => 'Manual',
      'CODE_SIGN_IDENTITY' => '-',
      'CODE_SIGNING_ALLOWED' => 'YES',
      'DEVELOPMENT_TEAM' => '',
      'PROVISIONING_PROFILE_SPECIFIER' => '',
      'ENABLE_DEBUG_DYLIB' => 'NO'
    })
    configuration.build_settings['CODE_SIGN_ENTITLEMENTS'] = 'App/Simulator.entitlements' if target == app
    configuration.build_settings['CODE_SIGN_ENTITLEMENTS'] = 'App/SimulatorTests.entitlements' if target.product_type == 'com.apple.product-type.bundle.ui-testing'
  end
end
project.save
