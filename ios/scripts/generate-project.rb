#!/usr/bin/env ruby
# Optional maintainer tool. Normal use only needs the checked-in .xcodeproj.
# gem install xcodeproj -v 1.27.0
require 'xcodeproj'

root = File.expand_path('..', __dir__)
path = File.join(root, 'WakeMeUp.xcodeproj')
project = Xcodeproj::Project.new(path, false, 77)
project.root_object.attributes['LastUpgradeCheck'] = '2600'
project.root_object.development_region = 'ko'
project.root_object.known_regions = ['ko', 'en', 'Base']
project.build_configurations.each do |configuration|
  configuration.build_settings['CLANG_ENABLE_MODULES'] = 'YES'
end

configuration = project.main_group.new_group('Configuration', 'Configuration')
common = configuration.new_file('Common.xcconfig')
shared = project.main_group.new_group('Shared', 'Shared')
shared_sources = Dir[File.join(root, 'Shared', '*.swift')].sort.map { |file| shared.new_file(File.basename(file)) }
privacy = shared.new_file('PrivacyInfo.xcprivacy')
package = project.new(Xcodeproj::Project::Object::XCLocalSwiftPackageReference)
package.relative_path = 'WakeCore'
project.root_object.package_references << package

def configure_target(project, root, common, package, shared_sources, privacy, name, platform)
  target = project.new_target(:application, name, platform, '26.0')
  # Swift imports link Foundation automatically. The gem's SDK-versioned framework
  # paths otherwise point to an older SDK that isn't installed with Xcode 26.
  target.frameworks_build_phase.files.to_a.each do |file|
    reference = file.file_ref
    file.remove_from_project
    reference.remove_from_project
  end
  group = project.main_group.new_group(name, name)
  sources = Dir[File.join(root, name, '**', '*.swift')].sort.map do |file|
    group.new_file(file.delete_prefix(File.join(root, name) + '/'))
  end
  target.add_file_references(sources + shared_sources)
  target.resources_build_phase.add_file_reference(group.new_file('Assets.xcassets'))
  target.resources_build_phase.add_file_reference(privacy)
  group.new_file('Info.plist')
  group.new_file("#{name}.entitlements")
  target.build_configurations.each do |configuration|
    configuration.base_configuration_reference = common
    settings = configuration.build_settings
    settings['INFOPLIST_FILE'] = "#{name}/Info.plist"
    settings['GENERATE_INFOPLIST_FILE'] = 'NO'
    settings['CODE_SIGN_ENTITLEMENTS'] = "#{name}/#{name}.entitlements"
    settings['PRODUCT_BUNDLE_IDENTIFIER'] = platform == :ios ? '$(WAKEMEUP_BUNDLE_ID)' : '$(WAKEMEUP_BUNDLE_ID).watchkitapp'
    settings['PRODUCT_NAME'] = '$(TARGET_NAME)'
    settings['SWIFT_EMIT_LOC_STRINGS'] = 'YES'
    settings['ENABLE_PREVIEWS'] = 'YES'
    if platform == :ios
      settings['TARGETED_DEVICE_FAMILY'] = '1,2'
      settings['SUPPORTED_PLATFORMS'] = 'iphoneos iphonesimulator'
      settings['SUPPORTS_MACCATALYST'] = 'NO'
      settings['SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD'] = 'NO'
    else
      settings['TARGETED_DEVICE_FAMILY'] = '4'
      settings['SUPPORTED_PLATFORMS'] = 'watchos watchsimulator'
      settings['SKIP_INSTALL'] = 'YES'
    end
  end
  product = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
  product.package = package
  product.product_name = 'WakeCore'
  target.package_product_dependencies << product
  build_file = project.new(Xcodeproj::Project::Object::PBXBuildFile)
  build_file.product_ref = product
  target.frameworks_build_phase.files << build_file
  project.root_object.attributes['TargetAttributes'] ||= {}
  project.root_object.attributes['TargetAttributes'][target.uuid] = {
    'CreatedOnToolsVersion' => '26.0', 'ProvisioningStyle' => 'Automatic',
    'SystemCapabilities' => { 'com.apple.HealthKit' => { 'enabled' => 1 } }
  }
  target
end

phone = configure_target(project, root, common, package, shared_sources, privacy, 'WakeMeUp', :ios)
watch = configure_target(project, root, common, package, shared_sources, privacy, 'WakeMeUpWatch', :watchos)
phone.add_dependency(watch)
embed = phone.new_copy_files_build_phase('Embed Watch Content')
embed.dst_subfolder_spec = '16'
embed.dst_path = '$(CONTENTS_FOLDER_PATH)/Watch'
file = embed.add_file_reference(watch.product_reference)
file.settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }
project.predictabilize_uuids
project.save

[phone, watch].each do |target|
  scheme = Xcodeproj::XCScheme.new
  scheme.doc.root.attributes['LastUpgradeVersion'] = '2600'
  scheme.configure_with_targets(target, nil, launch_target: true)
  scheme.save_as(path, target.name, true)
end
puts "Generated #{path} (iPhone/iPad + Apple Watch)"
