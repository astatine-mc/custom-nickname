# CustomNickname 작업 규칙

이 문서는 소스 저장소 루트의 **`Agents.md`**이다. 사용자 지정 파일명이며 `AGENT.md`와 다르다. 설계는 [DESIGN.md](DESIGN.md), 운영·구현 현황은 [README.md](README.md)를 먼저 확인한다. 서버 플러그인 설정 폴더에는 이 문서를 자동 배치하지 않는다.

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
- 동작을 바꾼 경우 해당 성공·실패·재시도를 검증한다. 주석·포맷만 바꾸는 경우 새 테스트를 억지로 추가하지 않고 기존 테스트와 컴파일을 확인한다.
- build output인 모든 `target/`은 Git에 추가하지 않는다.

## 상세 주석과 문서 검증

- 메서드마다 입력, 부작용, 실행 스레드, 실패 전달 중 해당되는 계약을 적는다. DB commit과 화면 반영의 차이를 명확히 한다.
- DTO에는 UUID·계정명·표시명·revision·토큰의 의미를 적고, 토큰 원문을 로그에 남기지 않는다.
- 기존 구현의 한계는 주석에서 숨기지 않는다. 전달 ACK, 외부 API, 캐시 TTL 등이 없으면 구현된 것처럼 기술하지 않는다.
- 긴 한 줄 메서드는 읽을 수 있는 여러 줄로 정리한다. 포맷 변경과 동작 변경을 구분해 검토한다.
- README에서 개발자 표기는 플러그인 메타데이터와 비교한다. Git 계정이나 커밋 작성자 이메일로 개인 신원을 추정하지 않는다.
- 산출물을 확인할 때 문서 존재와 파일명 대소문자, 상대 링크, 실제 JAR 배치 경로를 확인한다.
- 서버 재시작 검증을 못 했으면 빌드 성공을 런타임 오류 해결로 표현하지 않는다. 과거 latest.log를 새 코드의 검증 근거로 재사용하지 않는다.
