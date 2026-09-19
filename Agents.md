# CustomNickname 작업 규칙

## 작업 전 확인

코드, 설정, 빌드 파일을 수정하기 전에 반드시 현재 서버 로그를 확인한다. Proxy와 lobby는 별도 프로세스이므로 한쪽 로그만 보고 원인을 판단하지 않는다.

```sh
rg -n -i "(error|exception|fatal|failed|severe|customnickname)" \
  /home/ubuntu/projectA/Proxy/logs/latest.log \
  /home/ubuntu/projectA/lobby/logs/latest.log
```

확인 순서:

1. `Proxy/logs/latest.log`에서 `customnickname` 시작, MariaDB 연결, plugin message 오류를 확인한다.
2. `lobby/logs/latest.log`에서 `CustomNicknameBridge`, `CustomNameplates`, PDC 아이템 처리 오류를 확인한다.
3. 오류가 다른 플러그인에 속하는지 확인하고, CustomNickname 코드·설정과 직접 관계가 없으면 해당 오류를 이 저장소 변경으로 해결하려 하지 않는다.
4. 수정 후 `mvn package`를 실행한다. 빌드는 Velocity JAR를 `Proxy/plugins`, Paper 브리지 JAR를 `lobby/plugins`로 자동 복사한다.
5. 서버를 재시작한 뒤 두 로그를 다시 읽고, `Loaded plugin customnickname`, `CustomNickname Velocity가 시작되었습니다`, `CustomNameplates placeholder를 등록했습니다`를 확인한다.

## 모듈 규칙

- `core/api`에는 비동기 계약과 값 객체만 둔다. Bukkit, Velocity, Hikari 타입을 import하지 않는다.
- `core/service`는 정책·캐시·비동기 제어를 맡는다. SQL을 직접 작성하지 않는다.
- `core/storage`는 JDBC와 스키마만 맡는다. 게임 플레이어 객체와 명령어를 참조하지 않는다.
- SQL 오류 분류는 `core/storage/sql/SqlErrorTranslator` 한 곳에서만 한다. 새 SQL 오류를 처리할 때 예외 메시지를 각 명령어에 중복하지 않는다.
- `velocity`는 권한, 명령어, MariaDB 시작, TAB, backend 전파를 맡는다.
- `paper-bridge`는 PDC 아이템, local display, CustomNameplates, Bukkit 이벤트를 맡는다. MariaDB에 접속하지 않는다.
- plugin message에는 `NicknameProtocol`의 허용된 타입만 사용하며, 새 타입은 버전·방향·필드 제한을 문서화하고 테스트한다.

## 코딩 규칙

- public 클래스와 외부에서 호출되는 메서드에는 책임, 입력의 신뢰 경계, 스레드 제약을 설명하는 Javadoc을 작성한다.
- 이름만 반복하는 주석 대신 왜 필요한지와 실패 시 동작을 쓴다.
- DB 예외 메시지·로그에 비밀번호, JDBC URL의 비밀 파라미터, 토큰 원문을 기록하지 않는다.
- 수정된 흐름에는 성공·실패·재시도 중 최소 하나의 테스트를 추가한다.
- build output인 모든 `target/`은 Git에 추가하지 않는다.
