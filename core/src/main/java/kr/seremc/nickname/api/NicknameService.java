package kr.seremc.nickname.api;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 닉네임 원본 서비스의 플랫폼 독립 계약입니다. 모든 DB 작업은 비동기로 완료되므로 게임 메인 스레드에서 {@code join()}이나 {@code get()}을 호출하지
 * 않습니다.
 */
public interface NicknameService {
  /** UUID로 현재 프로필을 조회합니다. */
  CompletableFuture<Optional<NicknameProfile>> findById(UUID id);

  /** 표시 닉네임으로 현재 프로필을 조회합니다. */
  CompletableFuture<Optional<NicknameProfile>> findByNickname(String nickname);

  /** 실제 Minecraft 계정명으로 현재 프로필을 조회합니다. */
  CompletableFuture<Optional<NicknameProfile>> findByAccountName(String accountName);

  /** 접속 시 계정명과 기본 닉네임을 동기화합니다. */
  CompletableFuture<NicknameProfile> synchronizeAccount(UUID id, String accountName);

  /** 유효한 변경권을 소비하고 닉네임을 변경합니다. */
  CompletableFuture<NicknameProfile> changeWithTicket(
      UUID id, String nickname, UUID token, UUID requestId);

  /** 감사 주체와 사유를 남기며 관리자 변경을 수행합니다. */
  CompletableFuture<NicknameProfile> forceChange(
      UUID id, String nickname, String actorType, String actorId, String reason, UUID requestId);

  /** 최신 이력부터 10건 단위로 조회합니다. */
  CompletableFuture<List<HistoryEntry>> history(UUID id, int page);

  /** 대상 UUID에만 사용할 수 있는 변경권을 발급합니다. */
  CompletableFuture<TicketIssue> issueTicket(String actorType, String actorId, UUID issuedTo);

  /** Paper 인벤토리 정리를 위해 변경권 상태를 일괄 조회합니다. */
  CompletableFuture<Map<UUID, TicketStatus>> ticketStatuses(List<UUID> tokens);

  /** DB 조회 없이 현재 Velocity 메모리 캐시에서만 조회합니다. */
  Optional<NicknameProfile> cached(UUID id);
}
