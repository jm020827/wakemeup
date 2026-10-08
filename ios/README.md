# wakemeup · Apple

SwiftUI로 구현한 iPhone·iPad·Apple Watch 앱입니다. **iOS/iPadOS 26 이상, watchOS 26 이상, Xcode 26 이상**을 사용합니다. Android와 같은 원칙으로 **실제 수면 기록의 첫 입면 + 목표 시간**에 기상 알람을 예약합니다.

## 화면

- **오늘 밤**: 달과 짧은 상태, 목표 시간 또는 예약된 기상시각, 워치 상태, 예비 알람, 하단의 시작·종료 버튼.
- **수면 흐름**: 밤 선택, 24시간 시계, 중요한 사건의 타임라인. 점과 목록을 누르면 같은 사건을 강조합니다. 입면·수신·예약·울림 확인·종료의 실제 시각을 보여 줍니다.
- **설정**: 권한·기기 관리, 진단 로그, CSV 내보내기.

iPhone은 네이티브 하단 탭, iPad는 사이드바와 넓은 기록 화면을 사용합니다. 시스템 밝기 모드와 어두운 모드, Liquid Glass 버튼, SF Symbols, 움직임 감소 설정을 지원합니다. 첫 실행에서 시스템 권한을 요청하고 선택을 완료하면 자동으로 메인 화면으로 이동합니다. 검증 모드·필수 메모·수동 활성화는 없습니다.

## Mac에서 열기와 설치

1. 저장소를 Mac에 가져온 뒤 **`ios/WakeMeUp.xcodeproj`**를 Xcode 26 이상에서 엽니다. 프로젝트 생성 도구나 외부 패키지 설치는 필요 없습니다.
2. **Signing & Capabilities**에서 `WakeMeUp`, `WakeMeUpWatch` 두 타깃의 **Team**을 선택합니다. HealthKit과 Background Delivery 설정은 프로젝트에 포함되어 있습니다.
3. iPad를 Mac에 연결하고, 필요하면 iPad의 **설정 → 개인정보 보호 및 보안 → 개발자 모드**를 켭니다.
4. 상단 스킴 **WakeMeUp**, 실행 기기 **내 iPad**를 선택하고 **⌘R**을 누릅니다.

Bundle ID를 바꿀 때는 `Configuration/Common.xcconfig`의 `WAKEMEUP_BUNDLE_ID`를 바꾸세요. 기본값은 `com.wakemeup.ios`이며 워치 ID와 동반 앱 ID도 함께 변경됩니다. 두 타깃은 같은 Team과 같은 앱 버전으로 서명합니다. 서명·프로비저닝 문제가 발생하면 Xcode에 표시된 HealthKit capability와 Team 설정을 확인하세요. 시뮬레이터 빌드는 서명이 필요 없습니다. TestFlight 배포에는 Apple Developer Program과 App Store Connect 설정이 필요합니다.

## 지금 iPad에서 확인할 것

1. 첫 실행의 **알람 허용**, **수면 기록 선택**을 완료합니다.
2. 오늘 밤에서 **예비 알람**을 현재보다 몇 분 뒤로 설정합니다.
3. **수면 감시 시작**을 누릅니다. **입면 대기**와 예비 알람 시각을 확인합니다.
4. 화면을 잠그고 정한 시각에 시스템 알람이 울리는지, 해제가 되는지 확인합니다.
5. **수면 흐름**에서 시작·울림 확인·해제 기록을 확인합니다.

**iPad 자체는 입면을 측정하지 않습니다.** Apple Watch도 iPad에 직접 페어링하지 않습니다. 워치 없이 쓰면 새 입면 기록이 생기지 않으며, 원래 입면 + 목표 시간 동작은 아직 확인할 수 없습니다. 같은 Apple 계정으로 동기화된 실제 워치 수면 기록이 건강 앱에 들어오면 iPad도 이를 읽을 수 있지만, 전달 시점은 별도 확인이 필요합니다.

## 나중에 iPhone + Apple Watch로 확인할 것

1. iPhone과 페어링된 Apple Watch에 `WakeMeUpWatch`를 설치하고 워치에서 한 번 엽니다. 워치의 수면 기록 읽기 선택도 완료합니다.
2. Apple의 수면 추적 설정을 준비하고, 자기 전에 iPhone에서 감시를 시작합니다. 워치 앱에서 **입면 대기**를 확인합니다.
3. 예비 알람을 유지한 상태로 실제 밤을 측정합니다. 다음날 원래 입면, 앱 수신시각, 지연, 기상 예약시각을 확인합니다.
4. 잠금 화면·수면 집중 모드·기기 간 연결 끊김·앱 재실행에서 예약과 기록이 유지되는지 확인합니다.

**HealthKit은 실시간 입면 이벤트를 보장하지 않습니다.** Apple Watch가 저장한 수면 구간을 읽고 WatchConnectivity로 전송하며, iPhone의 건강 앱에 동기화된 기록도 읽습니다. `.immediate` 백그라운드 수신 요청은 즉시 측정·동기화를 보장하는 옵션이 아닙니다. 따라서 Android와 동일한 계산·화면은 구현했지만, 같은 밤의 자동 기상 동작은 iPhone + Apple Watch 실기기 시험을 통과해야 합니다. 운영체제에서 앱을 강제 종료한 상태의 새 수면 기록 수신도 보장하지 않습니다. 이미 시스템이 수락한 알람 예약과 새 입면 기록 수신은 별개의 동작입니다.

## 데이터와 알람

- 최초 유효 입면만 유지합니다. 수신 시각을 입면으로 대체하지 않습니다. 이전 세션·다른 워치·시작 전 입면·미래 시각·중복·중간 각성 후 새 입면을 거릅니다.
- 감시 시작 시 예비 알람을 AlarmKit에 예약합니다. 목표 알람 예약 성공을 저장한 뒤 예비 알람을 해제합니다. 예약 실패와 늦은 수신에는 원래 입면과 예비 알람을 유지합니다.
- 계산된 기상시각이 지났다면 새 목표 알람을 즉시 울리지 않습니다. 취소 실패는 기록하고 재시도하며, 미해제 예약을 새 밤으로 숨기지 않습니다.
- WatchConnectivity의 최신 명령, 저장된 outbox, 수신 확인(ACK)으로 재전송을 처리합니다. 워치의 최초 입면은 ACK 후에도 유지해 중복 전송을 막습니다.
- 시계의 보라색 구간은 **입면 이후 경과시간**입니다. 수면 단계나 각성을 추정하지 않습니다. 예정은 빈 점, 실제 사건은 채운 점입니다.
- **울림 확인**은 앱이 AlarmKit의 실제 `.alerting` 상태를 관측한 시각입니다. 과거 예약이나 목록에서 사라진 알람을 근거로 울림 기록을 만들어 넣지 않습니다. OS가 알람을 끈 방법에 따라 앱에 해제 Intent가 전달되지 않을 수도 있습니다.
- 시각은 UTC epoch milliseconds로 저장하고 현지 시각으로 표시합니다. 저장 파일은 앱의 Application Support에 있으며 서버·계정·분석 SDK를 사용하지 않습니다. 앱 삭제 시 로컬 기록도 삭제됩니다.
- HealthKit은 읽기 권한 거절 여부를 앱에 공개하지 않습니다. UI의 **선택 완료**는 시스템 선택 절차를 마쳤다는 뜻입니다. 기록이 비어 있으면 건강 앱의 수면 읽기 선택과 실제 수면 기록을 확인하세요.

## 개발과 확인

| 경로 | 역할 |
| --- | --- |
| `WakeMeUp` | iPhone/iPad SwiftUI, AlarmKit, 권한, 로컬 기록 |
| `WakeMeUpWatch` | 워치 화면, HealthKit 관찰, 지속 가능한 outbox |
| `Shared` | HealthKit와 WatchConnectivity 어댑터 |
| `WakeCore` | 순수 Swift 계산·세션·복구·표현·워치 상태와 테스트 |
| `Configuration` | 공통 앱 버전·Bundle ID |

```bash
# Mac: 공통 로직 테스트 + iOS/워치 시뮬레이터 SDK 빌드
bash ios/scripts/verify-macos.sh

# Swift 6.2 이상이 설치된 Linux/Mac: 공통 로직만 빌드·테스트
swift test --package-path ios/WakeCore
```

Xcode Canvas는 `WakeMeUp/UI/Previews.swift`에서 오늘 밤의 밝은·어두운 화면과 수면 흐름을 제공합니다. 예시 기록은 Preview 전용이며 실제 앱이 생성하거나 읽지 않습니다. iPad 시뮬레이터를 선택하면 넓은 화면도 확인할 수 있습니다.

공통 로직 **34개 테스트**가 Linux의 Swift 6.2.3에서 통과했습니다. Apple 앱 소스의 Swift 문법, Xcode 프로젝트 구조와 리소스 참조도 확인했습니다. **이 작업 환경에는 Xcode/Apple SDK가 없으므로 전체 앱의 SDK 빌드·서명·실기기 동작은 아직 확인되지 않았습니다.** `.ipa` 설치 파일도 아직 생성하지 않았습니다.

체크인된 프로젝트를 재생성할 때만 Ruby의 `xcodeproj` 1.27.0과 `scripts/generate-project.rb`를 사용합니다. 원본 앱 아이콘 재생성은 Pillow를 설치한 후 `python3 ios/scripts/generate-assets.py`로 실행합니다. 보통의 Xcode 사용에는 이 도구들이 필요 없습니다.

## Apple 공식 자료

- [AlarmKit](https://developer.apple.com/documentation/alarmkit/scheduling-an-alarm-with-alarmkit)
- [HealthKit observer와 백그라운드 수신](https://developer.apple.com/documentation/healthkit/executing-observer-queries)
- [WatchConnectivity](https://developer.apple.com/documentation/watchconnectivity/transferring-data-with-watch-connectivity)
- [Liquid Glass](https://developer.apple.com/documentation/TechnologyOverviews/adopting-liquid-glass)
- [watchOS 앱과 iOS 동반 앱 구성](https://developer.apple.com/documentation/technotes/tn3157-updating-your-watchos-project-for-swiftui-and-widgetkit)
