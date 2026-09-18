# 커스텀 한글 닉네임 Velocity 전환안

현재 구조는 각 Paper 서버가 MariaDB와 캐시를 직접 사용합니다. 크로스서버 이동, 귓속말, 관리자 명령, 닉네임 중복 검사를 일관되게 처리하려면 Velocity를 중앙 서비스로 두고 Paper 서버에는 표시와 아이템 처리만 남기는 편이 적합합니다. 단일 Velocity 인스턴스에서는 Velocity 메모리 캐시와 MariaDB만 사용합니다.

## 목표 구조

```text
                         ┌─────────────────────────────┐
                         │ Velocity CustomNickname     │
                         │                             │
플레이어 ── 접속/명령 ──▶│ NicknameService              │
                         │ MariaDB 원본                 │
                         │ 메모리 캐시                   │
                         │ 권한·변경권·관리자 도구       │
                         └──────────────┬──────────────┘
                                        │ Plugin Messaging
                         ┌──────────────▼──────────────┐
                         │ Paper Nickname Bridge        │
                         │                             │
                         │ display/tab/nameplate 표시   │
                         │ 변경권 아이템 생성·사용       │
                         │ 서버 플러그인 연동 이벤트     │
                         └─────────────────────────────┘
```

Velocity 플러그인이 닉네임의 원본 서비스가 됩니다. Paper 브리지는 닉네임을 저장하거나 최종 권한을 판단하지 않고, Velocity가 보낸 최신 프로필을 표시합니다.

현재 프록시에 설치된 `TAB v6.1.3`이 tab list의 최종 렌더러가 됩니다. 커스텀 닉네임 플러그인은 TAB의 tab list 이름을 직접 덮어쓰지 않고, TAB Developer API에 플레이어 placeholder를 등록한 뒤 TAB 설정의 `customtabname`에서 그 placeholder를 사용합니다. 네임태그는 백엔드의 `CustomNameplates` 플러그인이 담당하므로 TAB과 책임을 분리합니다.

## 구성 요소

### 1. Velocity 플러그인: `custom-nickname-velocity`

다음 책임을 가집니다.

- 접속한 플레이어의 UUID와 현재 Mojang 계정명을 확인합니다.
- MariaDB에서 프로필을 생성하거나 최신 계정명을 동기화합니다.
- 기본 닉네임과 직접 설정한 커스텀 닉네임을 구분합니다.
- 변경권 사용, 닉네임 중복 검사, 변경 이력 기록을 MariaDB 트랜잭션으로 처리합니다.
- Velocity 메모리 캐시를 UUID 프로필 조회에 사용하고, 변경 시 접속 중인 플레이어에게 Plugin Messaging으로 전파합니다.
- `/닉네임 조회`, `/닉네임 변경`, `/닉관리`를 프록시에서 처리합니다.
- `/귓속말`, `/msg`, 친구·파티·차단 시스템이 사용할 UUID 조회 API를 제공합니다.
- 닉네임 변경 후 접속 중인 모든 서버의 해당 플레이어에게 최신 프로필을 전파합니다.
- 플레이어가 서버를 이동할 때 새 Paper 서버로 최신 프로필을 다시 보냅니다.
- TAB v6.1.3의 API에 `%customnickname_nickname%` 플레이어 placeholder를 등록합니다.
- 평상시 TAB placeholder 갱신 빈도를 낮게 유지하고, 닉네임 변경 성공 직후에만 해당 플레이어를 한 번 즉시 갱신합니다.
- TAB reload 중에는 낮은 갱신 빈도와 마지막 정상 표시 값을 유지하고, reload 완료 후 한 번만 최신 프로필을 재적용합니다.

Velocity 명령어는 프록시에서 한 번만 등록하므로 서버별 권한과 명령어 충돌이 줄어듭니다. 관리자 명령은 플레이어가 어느 백엔드 서버에 있든 동일하게 동작합니다.

### 2. Paper 플러그인: `custom-nickname-bridge`

다음 책임만 가집니다.

- Velocity 플러그인 메시지를 수신해 월드 안의 `displayName`을 갱신합니다. tab list 이름은 프록시의 TAB 플러그인이 담당하므로 Paper에서 `playerListName`을 변경하지 않습니다.
- 설치된 `CustomNameplates`에 최신 커스텀 닉네임을 전달해 머리 위 이름을 갱신합니다.
- 평상시 CustomNameplates의 닉네임 갱신 빈도를 낮게 유지하고, 닉네임 변경 성공 직후에만 해당 플레이어를 한 번 즉시 갱신합니다.
- CustomNameplates reload 중에는 마지막 정상 네임플레이트를 유지하고, reload 완료 후 한 번만 최신 프로필을 재적용합니다.
- 채팅, HUD 등 다른 표시 플러그인에 연결할 `NicknameUpdatedEvent`를 발생시킵니다.
- 변경권 아이템의 생성과 인벤토리 사용을 처리합니다.
- 아이템에 저장된 변경권 토큰을 Velocity로 전달합니다.
- Velocity의 승인 응답을 받은 뒤 현재 인벤토리에서 사용한 아이템을 제거합니다.
- 다른 Paper 플러그인이 최신 닉네임을 조회할 수 있도록 로컬 캐시와 API를 제공합니다.

Paper 브리지는 MariaDB에 직접 접속하지 않습니다. DB 연결과 메모리 캐시는 Velocity 한 곳에서만 관리합니다.

## 데이터 책임

MariaDB는 변경의 최종 원본이며, 웹사이트와 Discord 봇도 같은 읽기 모델을 사용할 수 있도록 외부 시스템에 종속되지 않는 UUID 중심 구조로 설계합니다.

### `cn_players`: 플레이어 기준 정보

| 컬럼 | 설명 |
|---|---|
| `player_uuid` | 외부 연계의 영구 식별자, `CHAR(36)` 또는 `BINARY(16)` primary key |
| `current_account_name` | 현재 Minecraft 계정명 |
| `account_name_key` | 대소문자·정규화 검색용 키 |
| `current_nickname` | 현재 표시할 커스텀 닉네임 |
| `nickname_key` | 중복 검사와 닉네임 검색용 키, unique |
| `is_custom` | 플레이어가 직접 지정한 닉네임인지 여부 |
| `revision` | 캐시 무효화와 변경 순서 확인용 증가 값 |
| `created_at`, `updated_at`, `last_seen_at` | 생성·수정·최근 접속 시각 |

계정명은 표시용 값일 뿐 외부 시스템의 join key가 아닙니다. 웹사이트 회원 연결, Discord 계정 연결, 귓속말 대상 저장은 모두 `player_uuid`를 사용합니다.

### `cn_nickname_history`: 변경 이력

| 컬럼 | 설명 |
|---|---|
| `history_id` | 증가하는 이력 ID |
| `player_uuid` | `cn_players.player_uuid` |
| `old_nickname`, `new_nickname` | 변경 전·후 닉네임 |
| `old_account_name`, `new_account_name` | 계정명 동기화 이력용 선택 값 |
| `actor_type` | `PLAYER`, `ADMIN`, `SYSTEM`, `WEB`, `DISCORD_BOT` |
| `actor_id` | 실행 주체 UUID, 관리자 ID, 웹 사용자 ID, Discord 사용자 ID 등 |
| `reason` | 변경 사유 |
| `request_id` | 중복 요청 추적용 외부 요청 ID, unique 권장 |
| `created_at` | 변경 시각 |

닉네임 이력은 삭제하지 않고 append-only로 저장합니다. 웹사이트와 Discord 봇은 이 테이블을 통해 과거 닉네임, 변경 주체, 변경 사유를 조회할 수 있습니다.

### `cn_nickname_tickets`: 변경권

| 컬럼 | 설명 |
|---|---|
| `ticket_id` | DB 내부 증가 ID |
| `token_hash` | 아이템에 들어간 토큰의 해시 값, 원문 토큰은 DB에 저장하지 않음 |
| `issued_by_type`, `issued_by_id` | 발급 주체 유형과 식별자 |
| `issued_to_uuid` | 지급 대상 UUID |
| `used_by_uuid` | 사용한 플레이어 UUID |
| `issued_at`, `used_at`, `expires_at` | 발급·사용·만료 시각 |
| `status` | `ISSUED`, `USED`, `EXPIRED`, `CANCELLED` |

토큰 원문은 Paper 아이템과 요청 메시지에만 존재하고 MariaDB에는 해시만 저장합니다. `token_hash`와 `status`에 unique/조건부 인덱스를 두어 복제 아이템이나 동시 요청을 차단합니다.

### `cn_external_links`: 외부 계정 연결

웹사이트와 Discord 봇 연계를 위해 외부 계정 연결을 닉네임 테이블에 섞지 않고 별도 테이블로 둡니다.

| 컬럼 | 설명 |
|---|---|
| `link_id` | 연결 ID |
| `player_uuid` | Minecraft 플레이어 UUID |
| `provider` | `WEBSITE`, `DISCORD` 등 |
| `external_subject` | 웹 사용자 ID 또는 Discord user ID |
| `verified_at` | 검증 시각 |
| `created_at`, `revoked_at` | 연결·해제 시각 |

`provider + external_subject`는 unique로 두고, 계정 연결·해제는 별도의 감사 로그를 남깁니다. Discord 표시명이나 웹사이트 닉네임은 Minecraft 커스텀 닉네임과 별도 값으로 취급합니다.

### `cn_audit_log`: 관리자와 외부 시스템 감사 로그

강제 변경, 변경권 발급·취소, 웹사이트·Discord 봇의 쓰기 요청은 다음 정보를 별도 감사 로그에 남깁니다.

- `audit_id`, `request_id`, `actor_type`, `actor_id`
- `action`, `target_uuid`, `before_json`, `after_json`
- `source_ip` 또는 `source_service`, `created_at`

웹사이트와 Discord 봇은 기본적으로 `cn_players`, `cn_nickname_history`, `cn_external_links`에 대한 읽기 전용 DB 계정을 사용합니다. 닉네임 변경이나 계정 연결 같은 쓰기는 직접 SQL을 실행하지 않고 인증된 웹 API 또는 내부 서비스 API를 호출합니다.

### 권장 MariaDB DDL

실제 구현에서는 모든 테이블을 `utf8mb4`와 `utf8mb4_bin`으로 생성해 한글을 보존하고 검색 키의 비교 규칙을 애플리케이션에서 명확하게 통제합니다. UUID는 외부 연계와 운영 조회의 가독성을 위해 `CHAR(36)`으로 시작하고, 데이터 규모가 커질 때 `BINARY(16)`으로 전환할 수 있습니다.

```sql
CREATE TABLE cn_players (
  player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,
  current_account_name VARCHAR(16) NOT NULL,
  account_name_key VARCHAR(64) CHARACTER SET ascii NOT NULL,
  current_nickname VARCHAR(32) NOT NULL,
  nickname_key VARCHAR(64) CHARACTER SET utf8mb4 NOT NULL,
  is_custom BOOLEAN NOT NULL DEFAULT FALSE,
  revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  last_seen_at DATETIME(3) NULL,
  PRIMARY KEY (player_uuid),
  UNIQUE KEY uq_cn_players_nickname (nickname_key),
  KEY ix_cn_players_account (account_name_key),
  KEY ix_cn_players_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE cn_nickname_history (
  history_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,
  old_nickname VARCHAR(32) NULL,
  new_nickname VARCHAR(32) NOT NULL,
  old_account_name VARCHAR(16) NULL,
  new_account_name VARCHAR(16) NULL,
  actor_type VARCHAR(20) CHARACTER SET ascii NOT NULL,
  actor_id VARCHAR(128) CHARACTER SET ascii NULL,
  reason VARCHAR(255) NOT NULL,
  request_id CHAR(36) CHARACTER SET ascii NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (history_id),
  UNIQUE KEY uq_cn_history_request (request_id),
  KEY ix_cn_history_player (player_uuid, history_id),
  CONSTRAINT fk_cn_history_player FOREIGN KEY (player_uuid) REFERENCES cn_players(player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE cn_nickname_tickets (
  ticket_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  token_hash BINARY(32) NOT NULL,
  issued_by_type VARCHAR(20) CHARACTER SET ascii NOT NULL,
  issued_by_id VARCHAR(128) CHARACTER SET ascii NULL,
  issued_to_uuid CHAR(36) CHARACTER SET ascii NULL,
  used_by_uuid CHAR(36) CHARACTER SET ascii NULL,
  status ENUM('ISSUED','USED','EXPIRED','CANCELLED') NOT NULL DEFAULT 'ISSUED',
  issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  used_at DATETIME(3) NULL,
  expires_at DATETIME(3) NULL,
  PRIMARY KEY (ticket_id),
  UNIQUE KEY uq_cn_ticket_token (token_hash),
  KEY ix_cn_ticket_target (issued_to_uuid, status),
  KEY ix_cn_ticket_used (used_by_uuid, used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE cn_external_links (
  link_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,
  provider VARCHAR(20) CHARACTER SET ascii NOT NULL,
  external_subject VARCHAR(128) CHARACTER SET ascii NOT NULL,
  verified_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  revoked_at DATETIME(3) NULL,
  PRIMARY KEY (link_id),
  UNIQUE KEY uq_cn_external_subject (provider, external_subject),
  KEY ix_cn_external_player (player_uuid, provider),
  CONSTRAINT fk_cn_external_player FOREIGN KEY (player_uuid) REFERENCES cn_players(player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE cn_audit_log (
  audit_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  request_id CHAR(36) CHARACTER SET ascii NULL,
  actor_type VARCHAR(20) CHARACTER SET ascii NOT NULL,
  actor_id VARCHAR(128) CHARACTER SET ascii NULL,
  action VARCHAR(64) CHARACTER SET ascii NOT NULL,
  target_uuid CHAR(36) CHARACTER SET ascii NULL,
  before_json JSON NULL,
  after_json JSON NULL,
  source_service VARCHAR(64) CHARACTER SET ascii NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (audit_id),
  KEY ix_cn_audit_target (target_uuid, audit_id),
  KEY ix_cn_audit_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

닉네임 변경 트랜잭션에서는 `cn_players`를 UUID로 잠근 뒤 `cn_nickname_tickets`의 `token_hash`를 조건부로 `ISSUED`에서 `USED`로 바꾸고, 프로필·이력·감사 로그를 함께 commit합니다. `request_id`는 웹 요청이나 Discord 명령의 재전송을 멱등 처리하는 데 사용합니다.

웹사이트와 Discord 봇은 `player_uuid`를 외부 사용자 테이블의 foreign key처럼 저장합니다. `current_nickname`은 현재 표시용 값이고, 연결 계정의 표시명이나 Discord nickname을 저장하는 컬럼은 별도로 둡니다. 외부 시스템에서 탈퇴하거나 연결을 해제해도 Minecraft 플레이어와 닉네임 이력은 삭제하지 않습니다.

다음 작업은 반드시 Velocity에서 MariaDB 트랜잭션으로 처리합니다.

1. 변경권 토큰이 아직 사용되지 않았는지 확인합니다.
2. 새 닉네임이 유효하고 다른 프로필에서 사용 중이지 않은지 확인합니다.
3. 프로필을 갱신합니다.
4. 변경권을 사용 처리합니다.
5. 이력을 기록합니다.

중간 단계에서 실패하면 전체 트랜잭션을 롤백합니다. 따라서 같은 변경권을 여러 서버에서 동시에 사용해도 한 요청만 성공합니다.

Velocity는 접속 중인 플레이어의 프로필을 메모리에 짧게 캐시합니다. 변경이 성공하면 캐시의 `revision`을 증가시키고 해당 플레이어의 현재 서버와 접속 중인 서버에 Plugin Messaging으로 새 프로필을 전송합니다. Velocity가 재시작되면 캐시는 비워지고 MariaDB에서 다시 읽습니다.

## 접속 및 서버 이동 흐름

### 최초 접속

1. Velocity가 UUID와 현재 계정명을 확인합니다.
2. 프로필이 없으면 계정명을 기본 닉네임으로 생성합니다.
3. 프로필이 있으면 계정명을 최신 값으로 갱신합니다.
4. `custom = false`이면 기본 닉네임도 새 계정명으로 갱신합니다.
5. `custom = true`이면 커스텀 닉네임을 유지합니다.
6. 접속 서버로 프로필을 전송합니다.

### 서버 이동

1. Velocity가 플레이어의 새 서버 연결 이벤트를 받습니다.
2. 현재 revision의 프로필을 조회합니다.
3. 새 Paper 서버에 프로필을 전송합니다.
4. Paper 브리지가 display name, tab name, 네임태그 연동 이벤트를 갱신합니다.

### 표시 플러그인 reload 복구

표시 플러그인의 reload는 닉네임 저장소의 변경으로 간주하지 않습니다. 평상시에도 TAB과 CustomNameplates의 polling 갱신 빈도를 낮게 유지하고, reload 중에는 마지막 정상 렌더링 값을 유지합니다. reload 직후에는 다음 순서로 표시를 복구합니다.

1. `custom-nickname-velocity`는 MariaDB를 다시 쓰지 않고 현재 메모리 캐시의 최신 `NicknameProfile`을 사용합니다.
2. reload 중에는 placeholder를 비우거나 원문으로 초기화하지 않고, TAB과 CustomNameplates가 마지막 정상 값을 유지하도록 합니다.
3. TAB의 `TabLoadEvent` 또는 동등한 plugin reload 완료 이벤트를 받으면 `%customnickname_nickname%` placeholder를 다시 등록합니다.
4. Paper 브리지가 CustomNameplates의 reload 완료 또는 player load 이벤트를 받으면 해당 서버의 온라인 플레이어 프로필을 한 번 재적용합니다.
5. reload 완료 이벤트를 제공하지 않는 버전이면 낮은 polling 주기로 복구를 기다리며, 캐시가 없는 경우에만 실제 계정명을 fallback으로 사용합니다.
6. 재적용 작업은 UUID와 revision을 비교하므로 오래된 reload 콜백이 최신 닉네임을 덮어쓰지 못합니다.

reload 복구는 표시 계층에만 적용합니다. 닉네임 변경 이력, 변경권 사용 기록, MariaDB 프로필 revision은 다시 생성하거나 증가시키지 않습니다.

### 닉네임 변경

1. 플레이어가 `/닉네임 변경 <이름>`을 Velocity에서 실행합니다.
2. Velocity가 변경권 사용 요청 또는 Paper 브리지에서 전달된 토큰을 받습니다.
3. MariaDB 트랜잭션으로 토큰·중복·이력·프로필을 처리합니다.
4. 성공한 새 revision을 Velocity 메모리 캐시에 저장합니다.
5. Velocity가 해당 플레이어의 현재 서버에 최신 프로필을 전송합니다.
6. 모든 Velocity 인스턴스가 접속 중인 해당 플레이어에게 변경을 전파합니다.
7. Paper 브리지가 표시명을 갱신하고 성공 응답 후 아이템을 제거합니다.

## 변경권 설계

변경권 아이템에는 다음 PDC 값을 저장합니다.

- 네임스페이스: `customnickname`
- 키: `ticket_id`
- 값: 변경권마다 새로 발급되는 UUID 고유 ID

Paper 브리지는 아이템의 외형이나 이름만으로 변경권을 인정하지 않습니다. 실제 사용 가능 여부는 Velocity가 MariaDB의 `cn_nickname_tickets`에서 확인합니다.

아이템의 `customnickname:ticket_id` 고유 ID를 SHA-256으로 해시한 값이 MariaDB의 `token_hash`와 연결됩니다. DB 내부 `ticket_id`는 운영용 증가 ID이고, 플레이어 아이템과 서버 간 메시지에는 UUID 고유 ID만 사용합니다.

Velocity는 고유 ID의 상태를 다음과 같이 처리합니다.

- `ISSUED`: 닉네임 변경에 사용할 수 있습니다. DB 트랜잭션 성공 후 `USED`로 변경합니다.
- `USED`: 변경을 실행하지 않고 `REMOVE_TICKET_ID` 응답을 보냅니다.
- `EXPIRED`, `CANCELLED`, 미등록 ID: 사용할 수 없으며 `REMOVE_TICKET_ID` 응답을 보냅니다.

Paper 브리지는 `REMOVE_TICKET_ID`를 받으면 현재 플레이어의 인벤토리, off-hand, cursor에서 같은 고유 ID를 가진 모든 아이템을 제거합니다. 동일 ID로 복제된 아이템도 하나가 사용된 순간 모두 제거 대상입니다. 재접속, 서버 이동, `BRIDGE_READY` 또는 표시 플러그인 reload 뒤에는 Paper가 보유 중인 ticket ID 목록을 보내고, Velocity는 `ISSUED`가 아닌 ID 목록을 반환해 즉시 정리합니다.

권장 흐름은 다음과 같습니다.

- 관리자 지급: Velocity가 토큰을 발급하고 현재 서버의 Paper 브리지에 아이템 생성 요청을 보냅니다.
- 플레이어 사용: Paper 브리지가 토큰과 새 이름을 Velocity에 전달합니다.
- 성공: Velocity가 승인 응답을 보내고 Paper 브리지가 해당 고유 ID의 아이템을 모두 제거합니다.
- 이미 사용됨/만료/취소/미등록: Velocity가 변경을 실행하지 않고 제거 응답을 보내며, Paper 브리지가 해당 고유 ID의 아이템을 모두 제거합니다.
- 실패: 아이템을 제거하지 않고 오류 메시지만 표시합니다.
- 서버 종료나 네트워크 단절: 토큰은 DB에 발급 기록이 남으므로 관리자가 복구 지급할 수 있습니다.

아이템 제거는 성공 응답을 받은 뒤에 수행합니다. 이미 사용된 고유 ID나 유효하지 않은 고유 ID는 사용하지 않고 즉시 제거합니다. 아이템 복제본은 같은 고유 ID로 다시 요청해도 DB에서 거절되고, 현재 인벤토리의 동일 ID 아이템은 모두 제거됩니다.

## Plugin Messaging 프로토콜

Velocity와 Paper 사이에는 버전이 명확한 메시지 계약을 사용합니다. 채널 예시는 `customnickname:main`입니다.

### Velocity → Paper

- `PROFILE_SYNC`: UUID, account name, nickname, custom 여부, revision
- `TICKET_ITEM_CREATE`: 토큰, 표시용 아이템 버전
- `NICKNAME_RESULT`: 성공 여부, 메시지, revision
- `REMOVE_TICKET_ID`: 사용됨·만료·취소·미등록 상태의 고유 ticket ID
- `TICKET_RECONCILE_RESULT`: `ISSUED`가 아닌 ticket ID 목록
- `PROFILE_INVALIDATE`: UUID

### Paper → Velocity

- `BRIDGE_READY`: Paper 서버 식별자와 프로토콜 버전
- `TICKET_USE`: 플레이어 UUID, 토큰, 새 닉네임
- `TICKET_INVENTORY_STATE`: 플레이어가 보유한 ticket ID 목록
- `PROFILE_REQUEST`: 플레이어 UUID
- `TICKET_DELIVERY_FAILED`: 토큰과 대상 UUID

메시지에는 항상 프로토콜 버전, 요청 ID, UUID를 포함합니다. 알 수 없는 버전이나 길이 초과 메시지는 즉시 거절하고 로그에 기록합니다. Paper가 신뢰할 수 없는 클라이언트 입력을 직접 처리하지 않도록 새 닉네임 검증은 Velocity에서도 다시 수행합니다.

## Velocity TAB 연동

프록시에는 이미 `Proxy/plugins/TAB v6.1.3.jar`가 설치되어 있으므로 tab list 표시는 `custom-nickname-velocity`와 TAB을 연결하는 방식으로 구현합니다. TAB의 공식 Developer API가 제공하는 플레이어 placeholder에 현재 닉네임을 반환하고, TAB이 tab list packet을 최종 렌더링합니다.

### Placeholder 계약

| Placeholder | 반환 값 | 갱신 시점 |
|---|---|---|
| `%customnickname_nickname%` | 현재 커스텀 닉네임 | 접속, 서버 이동, 닉네임 변경, TAB reload |
| `%customnickname_account%` | 실제 Minecraft 계정명 | 접속 또는 계정명 변경 |
| `%customnickname_is_custom%` | `true` 또는 `false` | 프로필 변경 |

placeholder는 TAB이 전달하는 플레이어 UUID로 Velocity의 닉네임 캐시를 조회합니다. placeholder 계산 함수에서 MariaDB를 직접 호출하지 않고, 프로필 동기화가 끝난 뒤 메모리 캐시를 갱신합니다. 캐시에 값이 없으면 실제 계정명을 fallback으로 반환해 `%customnickname_nickname%` 문자열이 화면에 노출되지 않게 합니다.

개념적인 등록 코드는 다음과 같습니다. 실제 import 패키지와 API artifact 버전은 설치된 TAB 버전과 맞춥니다.

```java
TabAPI.getInstance().getPlaceholderManager().registerPlayerPlaceholder(
    "%customnickname_nickname%",
    1000,
    tabPlayer -> nicknameCache.get(tabPlayer.getUniqueId())
        .map(NicknameProfile::nickname)
        .orElseGet(tabPlayer::getName)
);
```

`1000`은 평상시 TAB placeholder 갱신 주기(ms) 예시입니다. 닉네임 변경 성공 직후에는 주기 갱신을 기다리지 않고 해당 플레이어의 TAB feature를 한 번 강제 refresh하지만, 평상시와 reload 중에는 낮은 빈도를 유지합니다. TAB API 호출은 임시 설정이므로 reload 완료 이벤트에서 등록 루틴을 다시 실행합니다. 캐시가 없는 경우에도 원문 placeholder 대신 실제 계정명을 반환합니다.

### TAB 설정 변경

현재 `Proxy/plugins/tab/groups.yml`의 `_DEFAULT_`에는 `customtabname: "%player%"`가 있습니다. Velocity 전환 후에는 다음처럼 변경합니다.

```yaml
_DEFAULT_:
  tabprefix: "%luckperms-prefix%"
  tagprefix: "%luckperms-prefix%"
  customtabname: "%customnickname_nickname%"
  tabsuffix: "%luckperms-suffix%"
  tagsuffix: "%luckperms-suffix%"
```

닉네임을 tab list 정렬 기준으로 사용하려면 `Proxy/plugins/tab/config.yml`의 정렬 설정도 다음처럼 바꿀 수 있습니다.

```yaml
scoreboard-teams:
  sorting-types:
    - "GROUPS:owner,admin,mod,helper,builder,vip,default"
    - "PLACEHOLDER_A_TO_Z:%customnickname_nickname%"
```

기존 prefix/suffix, LuckPerms 그룹, TAB animation은 유지합니다. 닉네임은 `customtabname`에만 넣어야 이름이 두 번 표시되지 않습니다. TAB의 tab list formatting이 활성화되어 있을 때 Paper 브리지와 다른 플러그인에서 `playerListName`을 직접 호출하지 않습니다.

### CustomNameplates 연동

설치된 `CustomNameplates-Bukkit-3.0.44.jar`는 백엔드 월드의 머리 위 이름을 담당합니다. TAB은 tab list만 담당하고, CustomNameplates는 `NicknameUpdatedEvent` 또는 Paper 브리지의 `applyProfile` 이벤트를 구독해 다음 값을 갱신합니다.

- 기본 이름 텍스트: `%customnickname_nickname%`에 해당하는 현재 닉네임
- 플레이어 식별: UUID 기준
- 계정명 fallback: 프로필이 아직 도착하지 않은 순간에만 실제 계정명 사용

CustomNameplates 설정이 자체 placeholder를 지원하면 Paper 브리지가 서버별 캐시에 `customnickname_nickname` 값을 등록합니다. 설정에서 해당 placeholder를 이름 텍스트에 사용합니다. 설정에서 직접 지원하지 않으면 브리지가 CustomNameplates의 공개 API를 사용해 UUID별 이름을 갱신합니다. NMS 패킷을 직접 수정하거나 TAB의 tab list 값을 재사용하지 않습니다. 평상시 CustomNameplates polling 갱신 빈도는 낮게 유지하고, 닉네임 변경 성공 직후에만 해당 플레이어를 한 번 즉시 갱신합니다. reload 중에는 캐시와 마지막 정상 UUID별 이름을 유지하고, reload 완료 뒤 한 번만 전체 프로필을 재적용합니다.

TAB과 CustomNameplates 모두에서 이름을 관리하므로 다음 책임을 지킵니다.

- TAB: 프록시 tab list의 `customtabname`
- CustomNameplates: 월드 머리 위 네임플레이트
- CustomNickname: UUID별 최신 닉네임 값과 변경 이벤트
- Paper 브리지: 두 표시 플러그인에 값을 전달하는 어댑터

CustomNameplates가 로드되지 않아도 닉네임 저장과 TAB 연동은 계속 동작해야 합니다. 브리지는 의존 플러그인이 없으면 경고를 기록하고 해당 표시 연동만 비활성화합니다.

## API 방향

Velocity API는 다른 프록시 플러그인이 사용할 중앙 계약입니다.

```java
CompletableFuture<Optional<NicknameProfile>> findById(UUID playerId);
CompletableFuture<Optional<NicknameProfile>> findByNickname(String nickname);
CompletableFuture<Optional<NicknameProfile>> findByAccountName(String accountName);
CompletableFuture<NicknameProfile> forceChange(
    UUID playerId, String nickname, String actor, String reason);
CompletableFuture<List<HistoryEntry>> history(UUID playerId, int page);
```

귓속말은 닉네임을 UUID로 먼저 해석한 뒤 UUID를 대상으로 전달합니다. 닉네임 변경 후에도 귓속말 대상이 다른 사람으로 바뀌지 않으며, 실제 계정명은 `@계정명` 같은 명시적 검색 형식으로 구분합니다.

Paper API는 표시용 계약으로 제한합니다.

```java
Optional<NicknameProfile> cachedProfile(UUID playerId);
void applyProfile(NicknameProfile profile);
```

Paper 메인 스레드에서 DB나 네트워크를 직접 호출하지 않습니다. Velocity API의 비동기 결과를 메인 스레드로 전환해 사용합니다.

## 권한과 보안

Velocity에서 최종 권한을 검사합니다.

- `customnickname.use`: 일반 닉네임 조회·변경
- `customnickname.admin`: 지급, 복구, 강제 변경, 이력 조회
- `customnickname.lookup`: 다른 플레이어 조회

Paper 브리지가 보내는 변경 요청에는 플레이어 UUID를 메시지 헤더에서 얻은 연결 정보와 대조합니다. 요청 본문에 적힌 UUID만 믿지 않습니다. 프록시와 백엔드 사이의 modern forwarding secret을 유지하고, 백엔드 포트는 외부에서 직접 접속할 수 없도록 방화벽으로 제한합니다.

## 현재 구현에서의 이동 범위

재사용할 코드:

- `NicknameProfile`, `HistoryEntry`, `NicknameService`의 도메인 모델과 API
- `NamePolicy`
- `MariaRepository`
- Velocity 메모리 캐시
- 동시성 테스트와 MariaDB 통합 테스트

Velocity로 이동할 코드:

- `DefaultNicknameService`
- MariaDB 초기화와 종료 처리
- 관리자 명령과 플레이어 명령
- 접속 시 계정명 동기화
- 변경 이력과 변경권 처리
- UUID·닉네임·계정명 조회

Paper 브리지에 남길 코드:

- `TicketItems`의 아이템 생성·검사 부분
- 월드 `displayName` 적용
- `CustomNameplates` 연동 어댑터
- Paper 이벤트와 Plugin Messaging 송수신
- 다른 Paper 플러그인용 `NicknameUpdatedEvent`

기존 단일 Bukkit 플러그인은 전환 기간 동안 호환 모드로 유지할 수 있지만, Velocity 플러그인이 활성화된 뒤에는 Bukkit 쪽 DB 연결과 명령어를 비활성화해야 합니다. 두 플러그인이 동시에 변경권을 소비하면 안 됩니다.

## 단계별 전환 순서

1. `custom-nickname-core` 모듈을 만들어 API, 정책, 저장소를 Bukkit·Velocity에서 공유합니다.
2. 현재 `custom-nickname`에서 도메인·저장소 코드를 core로 이동합니다.
3. `custom-nickname-velocity`를 추가하고 MariaDB와 모든 명령어를 이전합니다.
4. `custom-nickname-bridge`를 추가하고 Paper 표시·아이템·메시지 기능만 남깁니다.
5. TAB v6.1.3에 `%customnickname_nickname%` placeholder를 등록하고 `Proxy/plugins/tab/groups.yml`의 `_DEFAULT_.customtabname`을 변경합니다.
6. Paper 브리지에서 `CustomNameplates` 연동과 월드 display name 갱신을 검증합니다.
7. Velocity에서 접속 시 동기화와 서버 이동 동기화를 검증합니다.
8. 변경권 동시 사용, 중복 닉네임, MariaDB 장애, Velocity 재시작, Paper 서버 재시작을 통합 테스트합니다.
9. 운영 배포 시 Velocity 플러그인을 먼저 설치한 뒤 Paper 브리지를 설치합니다.
10. 모든 서버에서 기존 Bukkit 명령어와 저장소 연결을 끄고 Velocity 명령어를 활성화합니다.

## 운영 시 주의사항

- MariaDB 스키마 변경은 모든 서버가 같은 버전의 플러그인을 사용하도록 준비한 뒤 적용합니다.
- Velocity 메모리 캐시는 원본 데이터가 아니므로 Velocity 재시작 뒤 MariaDB에서 프로필을 다시 읽을 수 있어야 합니다.
- 웹사이트와 Discord 봇은 운영 DB 계정과 분리된 읽기 전용 계정을 사용하고, 쓰기 작업은 인증된 API를 통해서만 수행합니다.
- 닉네임 검색은 표시 이름이 바뀌어도 과거 이력의 이름을 현재 대상 검색으로 사용하지 않습니다. 과거 이름 검색이 필요하면 별도 history 검색 API를 추가합니다.
- Velocity가 재시작되어도 MariaDB 커밋은 보존됩니다. 변경 요청 응답이 끊긴 경우 `/닉네임 조회`와 `/닉관리 기록`으로 결과를 확인합니다.
- TAB에서는 `customtabname`만 커스텀 닉네임 placeholder로 바꾸고 `tabprefix`에는 닉네임을 넣지 않습니다.
- Paper에서는 `playerListName`을 직접 변경하지 않고 TAB에 맡깁니다.
- CustomNameplates가 UUID 기준으로 이름을 갱신하는지 확인하고 `PROFILE_SYNC` 수신 이벤트에 연동합니다.
- 평상시 TAB과 CustomNameplates의 갱신 빈도가 낮게 설정되어 마지막 정상 값이 유지되는지 확인합니다.
- TAB reload 후 원문 placeholder가 노출되지 않고, reload 완료 뒤 placeholder가 한 번 재적용되는지 확인합니다.
- CustomNameplates reload 후 빈 이름이 노출되지 않고, reload 완료 뒤 머리 위 이름이 한 번 재적용되는지 확인합니다.

## 결론

닉네임의 저장·권한·변경·검색은 Velocity에서 단일화하고, MariaDB는 웹사이트와 Discord 봇이 연계할 수 있는 공용 원본으로 사용합니다. Paper에는 월드 표시와 변경권 아이템 처리를 남깁니다. tab list는 프록시의 TAB v6.1.3이 `%customnickname_nickname%` placeholder를 통해 최종 렌더링하고, 월드 머리 위 이름은 `CustomNameplates`가 렌더링합니다. 이 구조가 현재 요구사항인 크로스서버 일관성, 빠른 조회, 귓속말 연동, 관리자 이력 관리에 가장 적합합니다.

## 참고 문서

- TAB Developer API: https://github.com/NEZNAMY/TAB/wiki/Developer-API
- TAB placeholder 등록: https://github.com/NEZNAMY/TAB/wiki/Placeholders
- TAB tab list 이름 구성: https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Tablist-name-formatting
- TAB 닉네임 플러그인 연동 안내: https://github.com/NEZNAMY/TAB/wiki/How-to-display-name-from-nickname-plugins
