require 'xcodeproj'

project_path = File.join(ENV.fetch('RUNNER_TEMP'), 'ios-consumer/ios/App/App.xcodeproj')
project = Xcodeproj::Project.open(project_path)
app = project.targets.find { |target| target.name == 'App' }
info_path = File.join(ENV.fetch('RUNNER_TEMP'), 'ios-consumer/ios/App/App/Info.plist')
info = Xcodeproj::Plist.read_from_path(info_path)
info['NSLocationDefaultAccuracyReduced'] = true
Xcodeproj::Plist.write_to_path(info, info_path)
tests = project.new_target(:unit_test_bundle, 'LocationTrackingPluginTests', :ios, '15.0')
tests.product_name = 'LocationTrackingPluginTests'
tests.product_reference.path = 'LocationTrackingPluginTests.xctest'
tests.add_dependency(app)
group = project.main_group.new_group('PluginTests')
Dir.glob(File.join(Dir.pwd, 'ios/Tests/LocationTrackingPluginTests/*.swift')).each do |path|
  tests.source_build_phase.add_file_reference(group.new_file(path))
end
package = project.new(Xcodeproj::Project::Object::XCLocalSwiftPackageReference)
package.relative_path = File.join(ENV.fetch('RUNNER_TEMP'), 'ios-consumer/node_modules/@bricks-soft/capacitor-location-tracking')
project.root_object.package_references << package
[app, tests].each do |target|
  product = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
  product.package = package
  product.product_name = 'BricksSoftCapacitorLocationTracking'
  target.package_product_dependencies << product
  build = project.new(Xcodeproj::Project::Object::PBXBuildFile)
  build.product_ref = product
  target.frameworks_build_phase.files << build
end
tests.build_configurations.each do |configuration|
  configuration.build_settings.merge!({
    'GENERATE_INFOPLIST_FILE' => 'YES',
    'PRODUCT_BUNDLE_IDENTIFIER' => 'com.brickssoft.locationtracking.tests',
    'SWIFT_VERSION' => '5.0',
    'PRODUCT_NAME' => '$(TARGET_NAME)',
    'PRODUCT_MODULE_NAME' => 'LocationTrackingPluginTests',
    'ENABLE_TESTING_SEARCH_PATHS' => 'YES',
    'TEST_HOST' => '$(BUILT_PRODUCTS_DIR)/App.app/App',
    'BUNDLE_LOADER' => '$(TEST_HOST)',
    'CODE_SIGNING_ALLOWED' => 'NO'
  })
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_test_target(tests)
scheme.save_as(project_path, 'Stage1', true)

ui = project.new_target(:ui_test_bundle, 'PermissionUITests', :ios, '15.0')
ui.product_name = 'PermissionUITests'
ui.product_reference.path = 'PermissionUITests.xctest'
ui.add_dependency(app)
Dir.glob(File.join(Dir.pwd, 'ios/UITests/*.swift')).each do |path|
  ui.source_build_phase.add_file_reference(group.new_file(path))
end
ui.build_configurations.each do |configuration|
  configuration.build_settings.merge!({
    'GENERATE_INFOPLIST_FILE' => 'YES',
    'PRODUCT_BUNDLE_IDENTIFIER' => 'com.brickssoft.locationtracking.uitests',
    'SWIFT_VERSION' => '5.0',
    'PRODUCT_NAME' => '$(TARGET_NAME)',
    'TEST_TARGET_NAME' => 'App',
    'CODE_SIGNING_ALLOWED' => 'NO'
  })
end
project.save
ui_scheme = Xcodeproj::XCScheme.new
ui_scheme.add_build_target(app)
ui_scheme.add_test_target(ui)
ui_scheme.save_as(project_path, 'Permissions', true)
