# CustomNickname 설계

소스 저장소 루트 `custom-nickname/DESIGN.md`에 있는 문서입니다. 사용법과 현재 구현 한계는 [README.md](README.md), 작업 절차는 [Agents.md](Agents.md)를 참고합니다.

## 책임 경계

| 모듈 | 책임 | 직접 알면 안 되는 것 |
|---|---|---|
| `core/api` | 외부 플러그인이 사용하는 조회·변경 계약과 값 객체 | Velocity·Paper 구현 세부 사항 |
| `core/service` | 입력 규칙, 비동기 실행, revision 캐시, 변경 후 알림 | JDBC 문장, Bukkit 객체 |
| `core/storage` | MariaDB 테이블, 트랜잭션, 토큰 해시, 이력 | 명령어·인벤토리·표시 UI |
| `core/storage/sql` | JDBC SQLState와 MariaDB 오류 코드의 분류 | 닉네임 정책 |
| `core/storage/error` | 안전한 운영·사용자 메시지를 가진 DB 오류 타입 | SQL 문장과 비밀번호 |
| `core/protocol` | Velocity↔Paper의 버전 있는 plugin message 인코딩 | DB 연결·게임 API |
| `velocity` | 접속 동기화, MariaDB 시작, 명령어, TAB, Paper 전파 | Paper 인벤토리 구현 |
| `paper-bridge` | PDC 변경권 아이템, 표시명, CustomNameplates, 로컬 이벤트 | MariaDB 직접 접속 |

`core`는 Java 라이브러리다. Paper·Velocity API 타입을 `core`에 추가하지 않는다. 새 플랫폼 기능은 해당 플랫폼 모듈에 어댑터로 두고, 공통 계약이 필요할 때만 `api`에 최소한의 값 객체나 인터페이스를 추가한다.

## 데이터 흐름

```mermaid
flowchart LR
  Player[플레이어] --> Velocity[Velocity 모듈]
  Velocity --> Service[core service]
  Service --> Storage[core storage]
  Storage --> MariaDB[(MariaDB)]
  Velocity -->|Plugin Message| Bridge[Paper 브리지]
  Velocity --> TAB[TAB: 탭 목록]
  Bridge --> Nameplates[CustomNameplates: 머리 위 이름]
  Bridge --> Event[NicknameUpdatedEvent]
```

Velocity가 닉네임 원본이다. Paper 브리지는 받은 revision보다 낮은 프로필을 적용하지 않으며 MariaDB에 연결하지 않는다. TAB 갱신이 1순위, CustomNameplates가 2순위, Bukkit `displayName`과 `NicknameUpdatedEvent`가 그 뒤를 따른다.

## 변경권 상태와 원자성

실제 Paper 아이템 키는 `customnicknamebridge:ticket_id`이다. `NamespacedKey(plugin, "ticket_id")`가 플러그인 이름을 namespace로 사용한다. 계획의 `customnickname:ticket_id`와 다르므로 키 변경에는 기존 아이템 호환 처리가 필요하다. MariaDB에는 UUID의 SHA-256 해시를 저장한다. 변경 시 프로필 잠금, 티켓 USED 전환, 프로필 수정, 이력·감사 기록을 하나의 트랜잭션으로 commit한다. 복제본의 DB 재사용은 차단되며 실제 제거는 사용 응답 또는 인벤토리 대조 시 수행된다.

## 현재 결합과 확장 지점

Maven 모듈은 core, velocity, paper-bridge 세 개이다. 세부 패키지를 별도 Maven 모듈로 분리한 상태는 아니다. DefaultNicknameService는 MariaRepository 구현을 직접 참조한다. DB 교체가 필요하면 저장소 인터페이스를 먼저 도입하고 정책 및 API는 유지한다.

프로필 캐시는 ConcurrentHashMap이며 TTL이나 외부 변경 알림이 없다. UUID 조회는 캐시, 닉네임 역조회는 DB이므로 외부 직접 쓰기를 허용하면 두 조회가 다를 수 있다. 웹·봇 쓰기 기능을 추가할 때 인증 API와 캐시 무효화를 함께 구현해야 한다.

## 비동기 실패 경계

서비스의 DB 작업과 알림은 작업 executor에서 실행된다. commit 후 표시 알림은 DB 트랜잭션에 포함되지 않는다. 표시 실패를 이유로 변경권을 재발급하면 안 된다. 반대로 DB 조회 실패를 UNKNOWN 티켓으로 바꾸면 정상 아이템이 삭제되므로 예외를 유지한다.

현재 requestId 재조회는 최초 성공 스냅샷 대신 현재 프로필을 반환한다. 전달 확인 ACK, timeout 상태 추적, 자동 재전송은 구현하지 않았다. 이를 추가할 때 requestId·플레이어·작업 내용 바인딩과 서버 이동 시 응답 대상 검증을 함께 설계한다.

## SQL 오류 처리

`SqlErrorTranslator`가 JDBC 오류를 다음 예외로 바꾼다.

| 조건 | 예외 | 운영 처리 |
|---|---|---|
| SQLState `28000`, MariaDB 1044/1045 | `DatabaseAuthenticationException` | DB 사용자·비밀번호·권한 확인 |
| SQLState `08xxx` | `DatabaseUnavailableException` | DB 호스트·포트·서버 상태 확인 후 재시도 |
| SQLState `40001`, 1205, 1213 | `DatabaseTransactionException` | 저장소가 두 번 재시도, 이후 사용자에게 재시도 안내 |
| SQLState `23xxx`, 1062 | `DatabaseConstraintException` | 닉네임 중복 또는 요청 중복 처리 |
| SQLState `42xxx`, `3D000`, 1146 | `DatabaseSchemaException` | 스키마 생성 권한·테이블 상태 확인 |

예외 메시지에는 비밀번호나 SQL 문장을 넣지 않는다. 원본 `SQLException`은 cause로 남아 Velocity 로그에서 SQLState와 vendor code를 확인할 수 있다.

## 표시 플러그인 reload

TAB과 CustomNameplates placeholder는 1초 주기로 갱신한다. 닉네임 변경 때는 즉시 한 번 갱신한다. reload 시에는 기존 placeholder를 찾은 경우에만 해제하고 다시 등록한다. 캐시에 프로필이 없을 때는 placeholder 원문 대신 실제 계정명을 반환한다.

## 빌드와 배치

`mvn package`는 테스트를 실행하고 다음 JAR를 자동 복사한다.

| 산출물 | 배치 위치 |
|---|---|
| `custom-nickname-velocity-*.jar` | `../Proxy/plugins` |
| `custom-nickname-paper-bridge-*.jar` | `../lobby/plugins` |

실행 중인 서버는 JAR를 교체한 것만으로 코드를 읽지 않는다. 각 서버를 안전하게 재시작한 뒤 로그에서 플러그인 시작 성공을 확인한다.
