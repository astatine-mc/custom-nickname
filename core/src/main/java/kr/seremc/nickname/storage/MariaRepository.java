package kr.seremc.nickname.storage;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import kr.seremc.nickname.api.*;
import kr.seremc.nickname.service.NamePolicy;
import kr.seremc.nickname.storage.error.DatabaseException;
import kr.seremc.nickname.storage.sql.SqlErrorTranslator;

/**
 * MariaDB의 영속 상태만 다루는 저장소입니다.
 *
 * <p>닉네임 규칙, Bukkit/Velocity API, plugin messaging은 이 클래스에 넣지 않습니다. 변경권 사용은 프로필 수정·이력·감사 로그와 같은 DB
 * 트랜잭션으로 확정합니다.
 */
public final class MariaRepository implements AutoCloseable {
  private final HikariDataSource pool;

  /** 주입된 풀의 생명주기를 소유합니다. 서비스 종료 시 close로 풀을 닫습니다. */
  public MariaRepository(HikariDataSource pool) {
    this.pool = pool;
  }

  /** 모든 네트워크 서버가 공유하는 UUID 중심 스키마를 한 번 준비합니다. */
  public void initialize() {
    try (Connection c = pool.getConnection();
        Statement s = c.createStatement()) {
      s.executeUpdate(
          """
CREATE TABLE IF NOT EXISTS cn_players (
  player_uuid CHAR(36) CHARACTER SET ascii PRIMARY KEY,
  current_account_name VARCHAR(16) NOT NULL,
  account_name_key VARCHAR(64) CHARACTER SET ascii NOT NULL,
  current_nickname VARCHAR(32) NOT NULL,
  nickname_key VARCHAR(64) NOT NULL,
  is_custom BOOLEAN NOT NULL DEFAULT FALSE,
  revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  last_seen_at DATETIME(3) NULL,
  UNIQUE KEY uq_cn_players_nickname(nickname_key),
  KEY ix_cn_players_account(account_name_key), KEY ix_cn_players_seen(last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
""");
      s.executeUpdate(
          """
CREATE TABLE IF NOT EXISTS cn_nickname_history (
  history_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,
  old_nickname VARCHAR(32), new_nickname VARCHAR(32) NOT NULL,
  old_account_name VARCHAR(16), new_account_name VARCHAR(16),
  actor_type VARCHAR(20) CHARACTER SET ascii NOT NULL,
  actor_id VARCHAR(128) CHARACTER SET ascii,
  reason VARCHAR(255) NOT NULL,
  request_id CHAR(36) CHARACTER SET ascii NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_cn_history_request(request_id),
  KEY ix_cn_history_player(player_uuid,history_id),
  CONSTRAINT fk_cn_history_player FOREIGN KEY(player_uuid) REFERENCES cn_players(player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
""");
      s.executeUpdate(
          """
CREATE TABLE IF NOT EXISTS cn_nickname_tickets (
  ticket_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  token_hash BINARY(32) NOT NULL,
  issued_by_type VARCHAR(20) CHARACTER SET ascii NOT NULL,
  issued_by_id VARCHAR(128) CHARACTER SET ascii,
  issued_to_uuid CHAR(36) CHARACTER SET ascii,
  used_by_uuid CHAR(36) CHARACTER SET ascii,
  status ENUM('ISSUED','USED','EXPIRED','CANCELLED') NOT NULL DEFAULT 'ISSUED',
  issued_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  used_at DATETIME(3), expires_at DATETIME(3),
  UNIQUE KEY uq_cn_ticket_token(token_hash),
  KEY ix_cn_ticket_target(issued_to_uuid,status), KEY ix_cn_ticket_used(used_by_uuid,used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
""");
      s.executeUpdate(
          """
CREATE TABLE IF NOT EXISTS cn_external_links (
  link_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,
  provider VARCHAR(20) CHARACTER SET ascii NOT NULL,
  external_subject VARCHAR(128) CHARACTER SET ascii NOT NULL,
  verified_at DATETIME(3), created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), revoked_at DATETIME(3),
  UNIQUE KEY uq_cn_external_subject(provider,external_subject),
  KEY ix_cn_external_player(player_uuid,provider),
  CONSTRAINT fk_cn_external_player FOREIGN KEY(player_uuid) REFERENCES cn_players(player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
""");
      s.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS cn_audit_log (
            audit_id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            request_id CHAR(36) CHARACTER SET ascii,
            actor_type VARCHAR(20) CHARACTER SET ascii NOT NULL,
            actor_id VARCHAR(128) CHARACTER SET ascii,
            action VARCHAR(64) CHARACTER SET ascii NOT NULL,
            target_uuid CHAR(36) CHARACTER SET ascii,
            before_json JSON, after_json JSON,
            source_service VARCHAR(64) CHARACTER SET ascii,
            created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            KEY ix_cn_audit_target(target_uuid,audit_id), KEY ix_cn_audit_request(request_id)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
          """);
    } catch (SQLException error) {
      throw SqlErrorTranslator.translate("닉네임 DB 스키마 초기화", error);
    }
  }

  /** 허용된 인덱스 열로만 단일 프로필을 조회해 SQL 식별자 주입을 막습니다. */
  public Optional<NicknameProfile> find(String field, String value) throws SQLException {
    if (!Set.of("player_uuid", "nickname_key", "account_name_key").contains(field))
      throw new IllegalArgumentException("Invalid lookup");
    try (Connection c = pool.getConnection();
        PreparedStatement s =
            c.prepareStatement("SELECT * FROM cn_players WHERE " + field + "=? LIMIT 2")) {
      s.setString(1, value);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return Optional.empty();
        NicknameProfile profile = profile(r);
        if (r.next()) throw new IllegalArgumentException("동일한 계정명이 여러 개입니다. UUID로 조회하세요.");
        return Optional.of(profile);
      }
    }
  }

  /** 접속 계정명을 최신화하고, 커스텀 닉네임이 없는 경우 기본 닉네임도 함께 갱신합니다. */
  public NicknameProfile synchronize(UUID id, String accountName) throws SQLException {
    if (!accountName.matches("[A-Za-z0-9_]{1,16}"))
      throw new IllegalArgumentException("유효하지 않은 계정명입니다.");
    return transaction(
        c -> {
          NicknameProfile old = lockedOptional(c, id).orElse(null);
          if (old == null) {
            try (PreparedStatement s =
                c.prepareStatement(
                    "INSERT INTO"
                        + " cn_players(player_uuid,current_account_name,account_name_key,current_nickname,nickname_key,last_seen_at)"
                        + " VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(3))")) {
              s.setString(1, id.toString());
              s.setString(2, accountName);
              s.setString(3, NamePolicy.key(accountName));
              s.setString(4, accountName);
              s.setString(5, NamePolicy.key(accountName));
              s.executeUpdate();
            }
            insertHistory(
                c,
                id,
                null,
                accountName,
                null,
                accountName,
                "SYSTEM",
                "VELOCITY",
                "FIRST_JOIN",
                UUID.randomUUID());
            return locked(c, id);
          }
          String nextNickname = old.custom() ? old.nickname() : accountName;
          boolean changed =
              !old.accountName().equals(accountName) || !old.nickname().equals(nextNickname);
          try (PreparedStatement s =
              c.prepareStatement(
                  "UPDATE cn_players SET"
                      + " current_account_name=?,account_name_key=?,current_nickname=?,nickname_key=?,last_seen_at=CURRENT_TIMESTAMP(3),revision=revision+?"
                      + " WHERE player_uuid=?")) {
            s.setString(1, accountName);
            s.setString(2, NamePolicy.key(accountName));
            s.setString(3, nextNickname);
            s.setString(4, NamePolicy.key(nextNickname));
            s.setInt(5, changed ? 1 : 0);
            s.setString(6, id.toString());
            s.executeUpdate();
          }
          if (changed)
            insertHistory(
                c,
                id,
                old.nickname(),
                nextNickname,
                old.accountName(),
                accountName,
                "SYSTEM",
                "VELOCITY",
                "ACCOUNT_SYNC",
                UUID.randomUUID());
          return locked(c, id);
        });
  }

  /** 변경권 사용, 닉네임 변경, 이력 기록을 하나의 원자적 작업으로 처리합니다. */
  public NicknameProfile changeWithTicket(UUID id, String nickname, UUID token, UUID requestId)
      throws SQLException {
    return transaction(
        c -> {
          Optional<NicknameProfile> replay = replay(c, id, requestId);
          if (replay.isPresent()) return replay.get();
          NicknameProfile old = locked(c, id);
          TicketStatus status = lockTicket(c, token, id);
          if (status != TicketStatus.ISSUED) throw rejected(status);
          if (old.nickname().equals(nickname)) throw new IllegalArgumentException("현재 닉네임과 같습니다.");
          try (PreparedStatement s =
              c.prepareStatement(
                  "UPDATE cn_nickname_tickets SET"
                      + " status='USED',used_by_uuid=?,used_at=CURRENT_TIMESTAMP(3) WHERE"
                      + " token_hash=? AND status='ISSUED'")) {
            s.setString(1, id.toString());
            s.setBytes(2, hash(token));
            if (s.executeUpdate() != 1)
              throw new TicketRejectedException(TicketStatus.USED, "이미 사용된 변경권입니다.");
          }
          updateProfile(c, id, nickname, true);
          insertHistory(
              c,
              id,
              old.nickname(),
              nickname,
              null,
              null,
              "PLAYER",
              id.toString(),
              "TICKET",
              requestId);
          insertAudit(
              c,
              requestId,
              "PLAYER",
              id.toString(),
              "NICKNAME_CHANGE",
              id,
              old.nickname(),
              nickname,
              "VELOCITY");
          return locked(c, id);
        });
  }

  /** 관리자의 강제 변경도 일반 변경과 같은 이력·감사 규칙을 적용합니다. */
  public NicknameProfile forceChange(
      UUID id, String nickname, String actorType, String actorId, String reason, UUID requestId)
      throws SQLException {
    return transaction(
        c -> {
          Optional<NicknameProfile> replay = replay(c, id, requestId);
          if (replay.isPresent()) return replay.get();
          NicknameProfile old = locked(c, id);
          if (old.nickname().equals(nickname)) throw new IllegalArgumentException("현재 닉네임과 같습니다.");
          updateProfile(c, id, nickname, true);
          insertHistory(
              c,
              id,
              old.nickname(),
              nickname,
              null,
              null,
              actorType,
              actorId,
              "ADMIN: " + reason,
              requestId);
          insertAudit(
              c,
              requestId,
              actorType,
              actorId,
              "FORCE_NICKNAME_CHANGE",
              id,
              old.nickname(),
              nickname,
              "VELOCITY");
          return locked(c, id);
        });
  }

  /** 무작위 UUID를 생성해 해시와 발급 주체·대상을 저장합니다. 반환된 token은 아이템 전달용이며, 이 메서드는 Paper 인벤토리에 접근하지 않습니다. */
  public TicketIssue issueTicket(String actorType, String actorId, UUID issuedTo)
      throws SQLException {
    UUID token = UUID.randomUUID();
    try (Connection c = pool.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO"
                    + " cn_nickname_tickets(token_hash,issued_by_type,issued_by_id,issued_to_uuid)"
                    + " VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
      s.setBytes(1, hash(token));
      s.setString(2, actorType);
      s.setString(3, actorId);
      s.setString(4, issuedTo == null ? null : issuedTo.toString());
      s.executeUpdate();
      try (ResultSet keys = s.getGeneratedKeys()) {
        if (!keys.next()) throw new SQLException("변경권 ID 생성 실패");
        return new TicketIssue(keys.getLong(1), token);
      }
    }
  }

  /**
   * 각 토큰을 해시로 조회합니다. 없는 행은 UNKNOWN, 경과한 만료 시각은 EXPIRED로 반환합니다. 조회 자체가 실패하면 SQLException을 전파하며
   * UNKNOWN 목록으로 대체하지 않습니다.
   */
  public Map<UUID, TicketStatus> ticketStatuses(List<UUID> tokens) throws SQLException {
    Map<UUID, TicketStatus> result = new LinkedHashMap<>();
    try (Connection c = pool.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT status,expires_at FROM cn_nickname_tickets WHERE token_hash=?")) {
      for (UUID token : tokens) {
        s.setBytes(1, hash(token));
        try (ResultSet r = s.executeQuery()) {
          result.put(token, r.next() ? effectiveStatus(r) : TicketStatus.UNKNOWN);
        }
      }
    }
    return Map.copyOf(result);
  }

  /** 최신 history_id 순서로 10건을 반환합니다. 페이지는 1부터 시작하며 OFFSET 범위를 검증합니다. */
  public List<HistoryEntry> history(UUID id, int page) throws SQLException {
    if (page < 1 || page > 1_000_000) throw new IllegalArgumentException("페이지는 1~1000000입니다.");
    try (Connection c = pool.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT * FROM cn_nickname_history WHERE player_uuid=? ORDER BY history_id DESC"
                    + " LIMIT 10 OFFSET ?")) {
      s.setString(1, id.toString());
      s.setInt(2, (page - 1) * 10);
      try (ResultSet r = s.executeQuery()) {
        List<HistoryEntry> list = new ArrayList<>();
        while (r.next())
          list.add(
              new HistoryEntry(
                  r.getLong("history_id"),
                  r.getString("old_nickname"),
                  r.getString("new_nickname"),
                  r.getString("actor_type"),
                  r.getString("actor_id"),
                  r.getString("reason"),
                  r.getTimestamp("created_at").toInstant()));
        return List.copyOf(list);
      }
    }
  }

  /** 토큰 행을 FOR UPDATE로 잠그고 만료·발급 대상을 확인합니다. 호출자가 시작한 동일 트랜잭션 안에서 사용해야 잠금이 commit까지 유지됩니다. */
  private TicketStatus lockTicket(Connection c, UUID token, UUID player) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT status,expires_at,issued_to_uuid FROM cn_nickname_tickets WHERE token_hash=?"
                + " FOR UPDATE")) {
      s.setBytes(1, hash(token));
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return TicketStatus.UNKNOWN;
        TicketStatus status = effectiveStatus(r);
        String target = r.getString("issued_to_uuid");
        if (status == TicketStatus.ISSUED && target != null && !target.equals(player.toString()))
          throw new TicketRejectedException(TicketStatus.UNKNOWN, "다른 플레이어에게 발급된 변경권입니다.");
        return status;
      }
    }
  }

  /** 저장 상태에 만료 시각을 적용합니다. EXPIRED 판정만 반환하며 DB status를 갱신하지 않습니다. */
  private TicketStatus effectiveStatus(ResultSet r) throws SQLException {
    TicketStatus status = TicketStatus.valueOf(r.getString("status"));
    Timestamp expires = r.getTimestamp("expires_at");
    if (status == TicketStatus.ISSUED
        && expires != null
        && expires.toInstant().isBefore(java.time.Instant.now())) return TicketStatus.EXPIRED;
    return status;
  }

  /** DB 상태를 사용자 안내와 제거 정책을 가진 변경권 예외로 바꿉니다. */
  private TicketRejectedException rejected(TicketStatus status) {
    return new TicketRejectedException(
        status,
        switch (status) {
          case USED -> "이미 사용된 변경권입니다.";
          case EXPIRED -> "만료된 변경권입니다.";
          case CANCELLED -> "취소된 변경권입니다.";
          default -> "등록되지 않은 변경권입니다.";
        });
  }

  /** 표시명과 정규화 검색 키, custom 플래그를 함께 변경하고 revision을 1 증가시킵니다. */
  private void updateProfile(Connection c, UUID id, String nickname, boolean custom)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "UPDATE cn_players SET"
                + " current_nickname=?,nickname_key=?,is_custom=?,revision=revision+1 WHERE"
                + " player_uuid=?")) {
      s.setString(1, nickname);
      s.setString(2, NamePolicy.key(nickname));
      s.setBoolean(3, custom);
      s.setString(4, id.toString());
      s.executeUpdate();
    }
  }

  /**
   * 동일 플레이어의 request_id가 이력에 있으면 현재 프로필을 반환합니다. 최초 응답의 스냅샷을 저장하는 방식은 아니므로 이후 변경된 현재 닉네임이 반환될 수
   * 있습니다.
   */
  private Optional<NicknameProfile> replay(Connection c, UUID id, UUID request)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT 1 FROM cn_nickname_history WHERE request_id=? AND player_uuid=?")) {
      s.setString(1, request.toString());
      s.setString(2, id.toString());
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(locked(c, id)) : Optional.empty();
      }
    }
  }

  /** UUID와 요청 ID를 포함한 이력을 추가합니다. 호출자가 제공한 트랜잭션의 commit에 포함됩니다. */
  private void insertHistory(
      Connection c,
      UUID id,
      String oldNick,
      String newNick,
      String oldAccount,
      String newAccount,
      String actorType,
      String actorId,
      String reason,
      UUID request)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO"
                + " cn_nickname_history(player_uuid,old_nickname,new_nickname,old_account_name,new_account_name,actor_type,actor_id,reason,request_id)"
                + " VALUES(?,?,?,?,?,?,?,?,?)")) {
      s.setString(1, id.toString());
      s.setString(2, oldNick);
      s.setString(3, newNick);
      s.setString(4, oldAccount);
      s.setString(5, newAccount);
      s.setString(6, actorType);
      s.setString(7, actorId);
      s.setString(8, reason);
      s.setString(9, request.toString());
      s.executeUpdate();
    }
  }

  /** 변경 전후 닉네임을 JSON_OBJECT로 저장해 JSON 문자열 이스케이프를 DB에 맡깁니다. */
  private void insertAudit(
      Connection c,
      UUID request,
      String actorType,
      String actorId,
      String action,
      UUID target,
      String before,
      String after,
      String source)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO"
                + " cn_audit_log(request_id,actor_type,actor_id,action,target_uuid,before_json,after_json,source_service)"
                + " VALUES(?,?,?,?,?,JSON_OBJECT('nickname',?),JSON_OBJECT('nickname',?),?)")) {
      s.setString(1, request.toString());
      s.setString(2, actorType);
      s.setString(3, actorId);
      s.setString(4, action);
      s.setString(5, target.toString());
      s.setString(6, before);
      s.setString(7, after);
      s.setString(8, source);
      s.executeUpdate();
    }
  }

  /** 프로필 행을 잠그고 없으면 접속 기록 없음 오류를 발생시킵니다. */
  private NicknameProfile locked(Connection c, UUID id) throws SQLException {
    return lockedOptional(c, id)
        .orElseThrow(() -> new IllegalArgumentException("접속 기록이 없는 플레이어입니다."));
  }

  /** 플레이어 행을 UUID로 잠급니다. 첫 접속의 행 부재는 Optional.empty로 반환합니다. */
  private Optional<NicknameProfile> lockedOptional(Connection c, UUID id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT * FROM cn_players WHERE player_uuid=? FOR UPDATE")) {
      s.setString(1, id.toString());
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(profile(r)) : Optional.empty();
      }
    }
  }

  /** JDBC 행을 불변 API 객체로 변환합니다. ResultSet 생명주기는 호출자가 관리합니다. */
  private NicknameProfile profile(ResultSet r) throws SQLException {
    return new NicknameProfile(
        UUID.fromString(r.getString("player_uuid")),
        r.getString("current_account_name"),
        r.getString("current_nickname"),
        r.getBoolean("is_custom"),
        r.getLong("revision"));
  }

  /** UUID의 상·하위 64비트를 고정 순서로 직렬화한 16바이트에 SHA-256을 적용합니다. */
  private static byte[] hash(UUID token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      ByteBuffer b =
          ByteBuffer.allocate(16)
              .putLong(token.getMostSignificantBits())
              .putLong(token.getLeastSignificantBits());
      return digest.digest(b.array());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 동일 Connection으로 묶여 실행할 저장소 작업입니다. 작업 내부에서 별도 commit을 호출하지 않습니다. */
  @FunctionalInterface
  private interface Work<T> {
    T run(Connection c) throws SQLException;
  }

  /** 데드락과 잠금 대기는 최대 두 번 재시도하고, 그 밖의 SQL 오류는 분류된 도메인 예외로 바꿉니다. */
  private <T> T transaction(Work<T> work) throws SQLException {
    for (int attempt = 0; ; attempt++) {
      try {
        return transactionOnce(work);
      } catch (SQLException e) {
        if (attempt >= 2 || (e.getErrorCode() != 1213 && e.getErrorCode() != 1205))
          throw translate(e);
      }
    }
  }

  /**
   * 자동 commit을 끄고 작업 전체를 commit합니다. SQL 또는 런타임 오류는 rollback하고 원래 예외를 유지합니다. rollback 실패는 suppressed
   * 예외로 보존하고 Connection은 항상 반환합니다.
   */
  private <T> T transactionOnce(Work<T> work) throws SQLException {
    try (Connection c = pool.getConnection()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        throw e;
      }
    }
  }

  /** 트랜잭션 재시도를 소진한 SQL 오류를 공통 분류기에 전달합니다. */
  private DatabaseException translate(SQLException error) {
    return SqlErrorTranslator.translate("닉네임 트랜잭션", error);
  }

  /** 저장소가 소유한 Hikari 풀을 닫습니다. 진행 중 서비스 작업 종료 후 호출해야 합니다. */
  @Override
  public void close() {
    pool.close();
  }
}
