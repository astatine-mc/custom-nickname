# Velocity · Paper/Purpur 설정 안내

이 문서는 CustomNickname을 Velocity 프록시와 Paper/Purpur lobby에 연결하기 위한 운영 설정이다. `spigot.yml`만 사용하는 순수 Spigot은 Velocity의 `modern` forwarding을 지원하지 않으므로, 이 구성에서는 Paper 또는 Purpur를 backend로 사용한다.

## 구성 관계

```text
클라이언트 → Velocity (25565, online-mode=true)
                 └→ lobby (127.0.0.1:25566, online-mode=false)
```

Velocity가 Mojang 인증과 UUID 전달을 맡고, lobby는 Velocity가 전달한 인증 정보를 신뢰한다. 그러므로 lobby 포트는 외부에서 직접 접근할 수 없게 제한해야 한다.

## 1. Velocity

대상 파일: `/home/ubuntu/projectA/Proxy/velocity.toml`

```toml
bind = "0.0.0.0:25565"
online-mode = true

player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:25566"

try = [
  "lobby"
]
```

`Proxy/forwarding.secret`에는 비어 있지 않은 한 줄의 비밀 문자열을 둔다. 새 문자열이 필요하면 다음처럼 생성할 수 있다.

```sh
openssl rand -hex 32 > /home/ubuntu/projectA/Proxy/forwarding.secret
```

이 파일은 비밀 값이므로 Git에 올리거나 채팅·로그에 출력하지 않는다.

## 2. Paper/Purpur lobby

### server.properties

대상 파일: `/home/ubuntu/projectA/lobby/server.properties`

```properties
online-mode=false
server-port=25566
```

같은 호스트에서 실행한다면 firewall로 `25566/tcp`를 localhost 또는 Velocity 프로세스만 접근하게 제한한다. `server-ip=127.0.0.1`을 사용할 수 있는 단일 호스트 구성에서는 backend를 localhost에만 bind할 수도 있다. 컨테이너·분리된 호스트 구성에서는 backend의 사설 IP를 사용하고 네트워크 방화벽 규칙으로 Velocity만 허용한다.

### paper-global.yml

대상 파일: `/home/ubuntu/projectA/lobby/config/paper-global.yml`

```yml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: "forwarding.secret 파일 내용 전체"
```

`secret`은 Velocity의 `Proxy/forwarding.secret` 파일 내용과 완전히 같아야 한다. 현재 두 설정의 값이 서로 다르므로, `forwarding.secret`의 값을 이 항목에 복사해 통일해야 한다. 공백, 줄 바꿈, 다른 따옴표 문자가 값에 포함되지 않도록 주의한다.

### spigot.yml

대상 파일: `/home/ubuntu/projectA/lobby/spigot.yml`

```yml
settings:
  bungeecord: false
```

이 값은 그대로 둔다. Velocity `modern` forwarding에서는 BungeeCord의 legacy forwarding을 켜지 않는다.

## 3. CustomNickname MariaDB

대상 파일: `/home/ubuntu/projectA/Proxy/plugins/customnickname/config.properties`

```properties
database.url=jdbc:mariadb://127.0.0.1:3306/minecraft?connectTimeout=3000&socketTimeout=5000
database.username=minecraft
database.password=
database.password-env=NICKNAME_DB_PASSWORD
database.pool-size=6
```

권장 방식은 Velocity를 실행하는 서비스 환경에 비밀번호를 환경 변수로 주는 것이다.

```sh
export NICKNAME_DB_PASSWORD='MariaDB 비밀번호'
```

서비스 관리자(systemd, Docker 등)를 사용한다면 Velocity 프로세스가 해당 환경 변수를 상속받도록 설정한다. 파일에 직접 비밀번호를 쓰려면 `database.password`에만 입력하고 `database.password-env=`로 비운다. 두 방식 중 하나만 사용한다.

현재 Proxy 로그에는 `minecraft@localhost`가 비밀번호 없이 연결했다는 MariaDB 인증 오류가 남아 있다. 비밀번호를 설정한 뒤 Proxy를 재시작해야 CustomNickname이 시작된다.

DB 계정에는 최소한 CustomNickname 테이블에 대한 `SELECT`, `INSERT`, `UPDATE`, `CREATE` 권한이 필요하다.

## 4. TAB · CustomNameplates

이미 적용된 파일은 다음과 같다.

| 플러그인 | 파일 | 핵심 설정 |
|---|---|---|
| TAB (Velocity) | `Proxy/plugins/tab/groups.yml` | `customtabname: "%customnickname_nickname%"` |
| TAB (Velocity) | `Proxy/plugins/tab/config.yml` | 닉네임 placeholder 및 1,000ms 갱신 |
| CustomNameplates (lobby) | `lobby/plugins/CustomNameplates/configs/nameplate.yml` | `player-name: "%customnickname_nickname%"` |
| CustomNameplates (lobby) | `lobby/plugins/CustomNameplates/config.yml` | 20틱 갱신 |

TAB을 Velocity에, CustomNameplates를 각 Paper/Purpur backend에 설치한다. CustomNickname Velocity JAR는 `Proxy/plugins/`에, Paper Bridge JAR는 `lobby/plugins/`에 둔다.

## 5. 재시작과 확인

설정 변경 뒤 lobby를 먼저 재시작하고, 그 다음 Velocity를 재시작한다. 서버 실행 중 설정 파일만 수정하거나 `/reload`로 적용하지 않는다.

다음 로그를 확인한다.

```sh
rg -n -i "customnickname|customnameplates|error|exception|failed" \
  /home/ubuntu/projectA/Proxy/logs/latest.log \
  /home/ubuntu/projectA/lobby/logs/latest.log
```

정상 시작 시 Proxy에는 `CustomNickname Velocity가 시작되었습니다`, lobby에는 `CustomNameplates placeholder를 등록했습니다`가 보여야 한다. forwarding secret 불일치, MariaDB 인증 실패, backend 직접 접속이 발생하면 그 상태에서 공개 운영하지 않는다.
