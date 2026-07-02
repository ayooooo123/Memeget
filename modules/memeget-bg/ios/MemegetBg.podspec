require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json'))) rescue { 'version' => '0.1.0' }

Pod::Spec.new do |s|
  s.name           = 'MemegetBg'
  s.version        = package['version'] || '0.1.0'
  s.summary        = 'Battery/thermal signals + background keep-alive for Memeget'
  s.description    = 'Native power/thermal reads and short background-execution extension.'
  s.author         = ''
  s.homepage       = 'https://github.com/ayooooo123/memeget'
  s.platforms      = { :ios => '15.1', :tvos => '15.1' }
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }

  s.source_files = '**/*.{h,m,mm,swift,hpp,cpp}'
end
