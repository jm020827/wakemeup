#!/bin/bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$task_root"
if [[ "$(uname -s)" != Darwin ]]; then
  echo "This check requires macOS with Xcode 26 or newer." >&2
  exit 1
fi
xcodebuild -version
mkdir -p .tools/ios
swift test --package-path ios/WakeCore --scratch-path .tools/ios/core-build
xcodebuild -project ios/WakeMeUp.xcodeproj -scheme WakeMeUp \
  -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .tools/ios/DerivedData CODE_SIGNING_ALLOWED=NO build
xcodebuild -project ios/WakeMeUp.xcodeproj -scheme WakeMeUpWatch \
  -configuration Debug -sdk watchsimulator -destination 'generic/platform=watchOS Simulator' \
  -derivedDataPath .tools/ios/WatchDerivedData CODE_SIGNING_ALLOWED=NO build
