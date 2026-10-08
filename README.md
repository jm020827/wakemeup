# wakemeup

워치가 추정한 **원래 입면시각 + 목표 시간**에 Android 휴대폰 알람을 울리는 앱입니다. [MVP 명세](sleep_onset_alarm_spec.md)를 Kotlin으로 구현했습니다.

예: 서울 시각 00:20 입면, 03:20 이벤트 수신, 목표 6시간 → 06:20 알람. 감시 시작 시각이나 이벤트 수신 시각부터 6시간을 세지 않습니다.

## 프로젝트

| 모듈 | 역할 |
| --- | --- |
| `core` | UTC `Instant` 모델, 세션 검증, 첫 입면 고정, 알람 교체·취소·복구, 시나리오 테스트 |
| `mobile` | Jetpack Compose 화면, DataStore 설정, Room 세션·로그, Data Layer 수신, `AlarmManager.setAlarmClock()`, 소리·진동·잠금 화면 해제 |
| `wear` | Compose for Wear OS 화면, 수면 상태 지원 확인, `PassiveListenerService`, 원래 입면시각의 영속 저장, Data Layer 전송, 재부팅 재등록 |

휴대폰과 워치의 application ID는 `com.wakemeup`으로 같습니다. 같은 인증서로 서명해야 Data Layer가 통신합니다. 서버·계정·자체 수면 추정 모델은 사용하지 않습니다.

## 빌드

JDK 21, Android SDK Platform 36, Build Tools 35.0.0이 필요합니다. Kotlin/Java 바이트코드 대상은 17입니다. 휴대폰은 Android 8.0(API 26) 이상, 워치는 Wear OS 3(API 30) 이상을 대상으로 합니다.

1. Android Studio에서 이 폴더를 열고 Gradle을 동기화합니다.
2. `local.properties`에 `sdk.dir=/실제/Android/SDK/경로`를 설정하거나 `ANDROID_HOME`을 지정합니다.
3. 아래 명령으로 두 앱을 빌드합니다.

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

결과:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

두 기기 각각에 설치합니다. `adb devices -l`에서 확인한 실제 기기 ID를 사용하세요.

```bash
adb -s PHONE_ID install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s WATCH_ID install -r wear/build/outputs/apk/debug/wear-debug.apk
```

## 첫 사용과 진행 조건

1. 워치에서 wakemeup을 열고 **신체 활동 권한**을 허용합니다. 수면 상태(`USER_ACTIVITY_ASLEEP`) 지원 여부가 휴대폰으로 전달됩니다. 미지원이면 감시 시작이 비활성화됩니다.
2. 휴대폰에서 **정확한 알람 접근**과 **알림**을 허용합니다. 알림의 ‘기상 알람’ 채널도 켜야 합니다. Android 14 이상에서 잠금 화면 전체 화면 알람 접근은 설정·검증 화면에서 확인합니다.
3. 목표 6시간 / 7시간 30분 / 9시간 또는 6시간 이상 직접 입력을 선택합니다. 필요하면 고정 시각 예비 알람을 켭니다. 시작할 때 다음 해당 현지 시각으로 한 번 예약합니다.
4. **수신 검증 시작**으로 실제 취침 테스트를 합니다. 검증 모드는 원래 입면·수신·계산된 기상시각을 저장하고, 예비 알람만 실제 예약합니다.
5. **설정·검증**에서 기상 전 원래 입면시각 수신, 추정 차이, 잠금·절전·수면모드의 소리·진동, 배터리 변화를 기록합니다. 유효한 실제 워치 이벤트가 있으며 수신이 계산된 기상보다 빠르고 사용자가 실제 결과를 확인한 경우에만 해당 워치의 자동 알람을 활성화할 수 있습니다.
   **검증할 수신 기록**에는 현재 워치의 가장 최근 수신 기록이 표시됩니다. 검증 감시를 종료하거나 새 세션을 시작해도 이전 기록으로 검증할 수 있으며, 기록이 여러 개면 **다른 수신 기록 선택**에서 해당 밤의 기록을 고릅니다. 검증 결과는 선택한 세션에 저장되며 진행 중인 새 세션의 모드는 바뀌지 않습니다.
6. 기존 검증 세션을 종료하고 **수신 검증 모드**를 끄면 **수면 감시 시작**으로 자동 알람을 사용합니다.

실기기 검증 완료 전에는 자동 목표 알람을 예약하지 않습니다. 지원 API가 있어도 특정 갤럭시 워치의 수면 상태 지원·정확도·기상 전 전달을 보장하지 않습니다. 기상 후에만 전달된다면 검증을 완료하지 말고 이 방식의 자동 알람 사용을 중단하세요.

## 동작과 복구

- 첫 유효 입면만 고정합니다. 이전 세션, 다른 워치, 감시 시작 전·미래 입면, 중복 및 중간 각성 후 입면은 무시합니다.
- `onsetAt`, 워치 수신 `watchReceivedAt`, 휴대폰 수신 `receivedAt`을 따로 저장합니다. 화면은 현지 시각, Room payload는 UTC ISO Instant, Data Layer는 UTC epoch milliseconds를 사용합니다.
- 목표 예약 의도를 먼저 저장하고 `setAlarmClock()` 예약 성공을 확인한 뒤 예비 알람을 교체합니다. 실패하면 오류와 첫 입면을 보존하고 예비 알람을 유지합니다.
- 계산된 기상시각이 현재 이하이면 목표 알람을 예약하지 않습니다. 과거 알람을 즉시 울리지 않습니다.
- 취소 상태를 먼저 저장해 이미 전달된 알람도 울리지 않게 한 다음 해당 세션의 두 알람과 워치 감시를 해제합니다.
- 재연결 시 Data Layer의 보관된 데이터와 워치의 영속 outbox를 동기화합니다. 새 세션은 이전 입면 outbox를 초기화하고 오래된 감시 명령을 무시합니다.
- 휴대폰 재부팅·앱 업데이트·정확한 알람 권한 재허용 시 저장한 시각으로 재예약합니다. 워치는 WorkManager에서 Health Services 감시를 재등록합니다. **WorkManager는 정확한 기상 예약에 사용하지 않습니다.**
- 알람은 media playback foreground service가 재생합니다. 기본 알람음, 반복 진동, 알림/잠금 화면 해제 버튼을 제공합니다.
- ‘수면 기록’에서 수신 지연·실패·재예약·알람 실행 오차·검증 메모를 확인하고 전체 로그를 CSV로 내보낼 수 있습니다.

## 검증

```bash
./gradlew :core:test :mobile:testDebugUnitTest :wear:testDebugUnitTest \
  :mobile:assembleDebug :wear:assembleDebug :mobile:lintDebug :wear:lintDebug
```

공통 테스트는 3시간 지연 수신, 첫 입면 고정, 이전 세션·다른 워치 거부, 과거 목표 처리, 예약 실패 시 예비 알람 유지, 취소, 재부팅, 연결 오류, 동시 중복 수신 및 DST를 확인합니다. Android 테스트는 Robolectric으로 실제 Room/AlarmManager 연동·권한 차단·PendingIntent 구분·워치 DataStore 복구를 확인합니다.

실제 수면 지원·잠금/절전/수면모드 소리와 진동·전달 지연·배터리는 실기기에서 별도로 확인해야 합니다. 자세한 절차는 [실기기 검증 가이드](docs/DEVICE_TESTING.md)를 참고하세요.

GitHub Actions 설정 예시는 [docs/ci/android.yml](docs/ci/android.yml)에 있습니다. 자동 검증을 활성화하려면 이 파일을 `.github/workflows/android.yml`로 복사하세요. Personal Access Token으로 푸시할 경우 워크플로 추가에는 `workflow` 권한이 필요합니다.

## 참고

- [Health Services 백그라운드 수집](https://developer.android.com/health-and-fitness/health-services/monitor-background)
- [Wear OS Data Layer 데이터 동기화](https://developer.android.com/training/wearables/data/data-items)
- [Android 알람 예약](https://developer.android.com/develop/background-work/services/alarms)
