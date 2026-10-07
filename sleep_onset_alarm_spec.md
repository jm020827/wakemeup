# 수면 시작 연동 알람 — MVP 명세

> **워치가 추정한 입면시각을 기준으로 N시간 뒤 휴대폰 알람을 울린다.**
> 입면 정보는 1.5~3시간 늦게 받아도 되며, 목표 기상시각 전에만 예약되면 된다.

## 1. 범위

대상은 **Android 휴대폰 + Wear OS 갤럭시 워치**이며, 보유 기종 하나부터 검증한다. 사용자가 취침 전 감시를 켜면 이후 알람 예약은 자동으로 처리한다.

N은 **입면 후 경과시간**으로, 중간에 깨어 있던 시간을 차감하지 않는다. REM 분석, 90분 주기 기반 최적 기상 보장, 자체 수면 추정 모델, 워치 단독 알람은 MVP에서 제외한다.

## 2. 기능 명세

| 기능 | 동작 |
|---|---|
| 설정 | 목표 시간 N: 6시간 / 7시간 30분 / 9시간 / 직접 입력(6시간 이상). 감지 실패용 고정 예비 알람은 선택 설정. |
| 감시 시작·취소 | 시작 시 새 세션 생성 및 예비 알람 예약. 시작 버튼을 누른 시각을 입면시각으로 사용하지 않음. 취소 시 해당 세션 알람 모두 해제. |
| 입면 정보 수신 | `USER_ACTIVITY_ASLEEP`의 `stateChangeTime`을 추정 입면시각으로 사용. 이벤트 수신시각과 별도로 저장.[1] |
| 알람 예약 | `alarmAt = onsetAt + N`. 유효한 정보 수신 후 목표 알람 예약에 성공하면 예비 알람을 교체. |
| 예외 처리 | 현재 세션의 첫 유효 입면시각으로 고정. 중복·이전 세션 이벤트는 무시하고, 중간 각성으로 시간을 다시 세지 않음. 계산된 알람시각이 이미 지났으면 자동 예약하지 않고 실패 기록·예비 알람 유지. |
| 알람·화면 | 휴대폰 소리·진동 및 해제 버튼. 감시 상태, 추정 입면시각, 예정 알람시각 표시. |

**예시:** 00:20 입면 → 03:20 정보 수신 → N=6시간이면 06:20 알람.

## 3. Architecture

```text
[휴대폰 UI] ── 감시 시작·설정 동기화 ──> [워치 앱]
                                              │
                              Health Services / PassiveListenerService
                                              │ 수면 상태 + stateChangeTime
                                              ▼
                             Wear OS Data Layer / DataClient
                                              │
                                              ▼
[휴대폰 수신 서비스] → [세션 검증·알람시각 계산] → [로컬 저장]
                                  │
                                  ▼
                     AlarmManager.setAlarmClock()
                                  │
                                  ▼
                     알람 수신기 → 소리·진동·해제 UI
```

모듈은 `mobile`(설정·예약·알람), `wear`(수면 이벤트 수집), `core`(공통 데이터·시간 계산)로 구분한다. 별도 서버·계정은 사용하지 않는다.

공통 데이터: `sessionId`, `monitorStartedAt`, `onsetAt`, `receivedAt`, `targetMinutes`, `alarmAt`, `status`. 시각은 UTC `Instant`로 저장하고 화면에서 현지 시각으로 변환한다.

## 4. 기술 스택

| 영역 | 선택 |
|---|---|
| 언어·UI | Kotlin, Coroutines/Flow, Jetpack Compose / Compose for Wear OS |
| 수면 이벤트 | AndroidX Health Services: `PassiveMonitoringClient` + `PassiveListenerService`[1] |
| 기기 간 통신 | Wear OS Data Layer: `DataClient` + `WearableListenerService`. 연결 복구 후 데이터 재동기화.[2] |
| 알람 | `AlarmManager.setAlarmClock()` + `BroadcastReceiver` + 알람 재생 서비스. 정확한 기상 예약에 WorkManager를 사용하지 않음.[3] |
| 저장·복구 | DataStore(설정), Room(세션·로그). 재부팅 시 알람 재예약·워치 감시 재등록.[1][3] |

## 5. 선행 검증 및 완료 기준

**진행 조건:** 실기기에서 수면 상태 지원 여부를 확인하고, 잠에서 깨기 전에 원래 입면시각이 전달되는지 먼저 검증한다. API 존재만으로 해당 갤럭시 워치의 지원·전달 지연·입면 정확도를 보장하지 않는다. 미지원 또는 기상 후에만 전달되면 이 설계의 자동 예약은 진행하지 않고, 별도 센서 기반 추정을 후속 과제로 분리한다.[1]

**권한:** 워치 `ACTIVITY_RECOGNITION`, 휴대폰 정확한 알람 접근 권한·알림 권한 및 알람 재생에 필요한 OS별 서비스 선언을 처리한다. 필수 권한이 없으면 정상 작동으로 표시하지 않는다.[1][3]

**완료 기준:** 입면 3시간 후 이벤트를 주입해도 원래 입면 기준으로 예약되며, 중복 이벤트·연결 복구·재부팅을 처리한다. 실제 취침 테스트에서 수신 지연, 입면 추정 차이, 알람 실행 오차, 배터리 소모를 기록하고 잠금·절전·수면모드에서 소리·진동을 확인한다.

## 공식 참고 문서

[1]: https://developer.android.com/health-and-fitness/health-services/monitor-background
[2]: https://developer.android.com/training/wearables/data/data-items
[3]: https://developer.android.com/develop/background-work/services/alarms

[1] [Health Services 백그라운드 수집·수면 상태][1] · [2] [Wear OS 데이터 동기화][2] · [3] [Android 알람 예약][3]
