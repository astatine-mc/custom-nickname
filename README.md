# CustomNickname 2

Velocity가 네트워크 전체 닉네임과 MariaDB를 관리하고, 각 Paper 서버의 가벼운 브리지가 변경권 아이템과 화면 표시를 담당합니다. Redis는 사용하지 않습니다. UUID가 영구 식별자이며 실제 계정명과 표시 닉네임을 따로 저장합니다.

## 구성과 설치

```sh
mvn clean package
```

- `velocity/target/custom-nickname-velocity-2.0.0-SNAPSHOT.jar`: Velocity `plugins`에 설치
- `paper-bridge/target/custom-nickname-paper-bridge-2.0.0-SNAPSHOT.jar`: 모든 Paper/Purpur 서버의 `plugins`에 설치
- `mvn package`를 실행하면 위 JAR가 작업 공간 루트의 `Proxy/plugins`와 `lobby/plugins`에도 각각 자동 복사됩니다.
- Velocity에는 TAB 6.x, Paper에는 CustomNameplates 3.x를 설치합니다. 두 연동 플러그인은 선택 의존성입니다.
- 모든 백엔드는 프록시가 전달한 동일한 UUID를 사용해야 합니다.

Velocity 플러그인의 `config.properties`에 MariaDB 연결 정보를 입력합니다. `NICKNAME_DB_PASSWORD` 환경변수가 있으면 파일의 비밀번호보다 우선합니다. 시작 시 아래 테이블을 생성합니다.

| 테이블 | 용도 |
|---|---|
| `cn_players` | UUID, 최신 계정명, 닉네임, 커스텀 여부, revision |
| `cn_nickname_history` | 최초 접속·계정 동기화·변경권·관리자 변경 이력 |
| `cn_nickname_tickets` | 변경권 고유 ID 해시, 발급 대상과 상태 |
| `cn_external_links` | 추후 웹사이트·Discord 계정 연결 |
| `cn_audit_log` | 외부 시스템도 조회할 수 있는 관리자/변경 감사 기록 |

## 동작

첫 접속 시 계정명을 기본 닉네임으로 저장합니다. 커스텀 닉네임이 없는 플레이어의 Mojang 계정명이 바뀌면 다음 접속 때 기본 닉네임도 최신 계정명으로 바뀝니다. 커스텀 닉네임은 유지됩니다. 실제 `Player#getName()`은 바꾸지 않습니다.

변경권 아이템은 PDC `customnickname:ticket_id`에 무작위 UUID를 가집니다. 닉네임 변경과 변경권 사용 및 이력 추가는 MariaDB 한 트랜잭션에서 처리됩니다. 접속하거나 서버를 이동할 때 Paper 브리지가 인벤토리의 ID를 Velocity에 보내며, DB 상태가 `ISSUED`가 아닌 ID는 같은 ID를 가진 복제본까지 모두 제거합니다. 표시만 같은 일반 이름표에는 효력이 없습니다.

표시 갱신 순서는 TAB, CustomNameplates, Bukkit `displayName`, 기타 `NicknameUpdatedEvent` 구독 기능 순입니다. TAB placeholder는 Velocity에서, CustomNameplates placeholder는 Paper에서 `%customnickname_nickname%`으로 제공합니다. 평상시 갱신 간격은 둘 다 1초이며 닉네임 변경 직후 즉시 한 번 갱신합니다. TAB 또는 CustomNameplates가 reload되면 placeholder를 재등록하고 캐시한 값을 계속 제공합니다.

현재 서버 설정에도 아래 항목을 반영했습니다.

- `Proxy/plugins/tab/groups.yml`: `customtabname: "%customnickname_nickname%"`
- `Proxy/plugins/tab/config.yml`: 닉네임 정렬 및 갱신 주기 1000ms
- `lobby/plugins/CustomNameplates/configs/nameplate.yml`: `player-name: "%customnickname_nickname%"`
- `lobby/plugins/CustomNameplates/config.yml`: 갱신 주기 20틱

## 명령어

| 명령어 | 설명 |
|---|---|
| `/닉네임 조회 [닉네임|@계정명|UUID]` | 저장 프로필 조회 |
| `/닉네임 변경 <새이름>` | 주 손의 유효한 변경권으로 변경 |
| `/닉관리 지급 <온라인계정명>` | 대상 전용 변경권 지급 |
| `/닉관리 강제변경 <대상> <새이름> <사유>` | 변경권 없이 변경하고 감사 이력 기록 |
| `/닉관리 기록 <대상> [페이지]` | 최신순으로 10건씩 조회 |

관리자 명령에는 `customnickname.admin` 권한이 필요합니다. 대상은 기본적으로 커스텀 닉네임이며, `@Steve`는 계정명, UUID 문자열은 UUID 조회입니다.

## 모듈 연동

`core`의 `NicknameService`는 UUID→닉네임, 닉네임→UUID, 계정명→UUID 조회를 `CompletableFuture`로 제공합니다. Paper 쪽 표시 확장은 `NicknameUpdatedEvent`를 구독할 수 있습니다.

```java
@EventHandler
public void onNicknameUpdated(NicknameUpdatedEvent event) {
    UUID playerId = event.profile().playerId();
    String nickname = event.profile().nickname();
}
```

## 검증

`mvn clean package`는 core 단위 테스트와 두 배포 JAR 생성을 수행합니다. 운영 적용 전 테스트 네트워크에서 최초 접속, 계정명 변경, 중복 닉네임, 같은 변경권 동시 사용, 서버 이동, TAB reload, CustomNameplates reload를 확인하세요.
