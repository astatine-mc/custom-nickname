package kr.seremc.nickname.service;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import kr.seremc.nickname.api.*;
import kr.seremc.nickname.storage.MariaRepository;
import kr.seremc.nickname.storage.sql.SqlErrorTranslator;

/**
 * 비동기 API, 입력 정책, 프로필 캐시를 조합하는 서비스 계층입니다. 저장소 작업은 고정 크기 executor에서 실행하므로 Velocity 또는 Paper 메인 스레드를
 * 막지 않습니다.
 */
public final class DefaultNicknameService implements NicknameService, AutoCloseable {
  private final MariaRepository repository;
  private final NamePolicy policy;
  private final Consumer<NicknameProfile> changed;
  private final ProfileCache cache = new ProfileCache();
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          4,
          4,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(256),
          Thread.ofPlatform().daemon().name("nickname-db-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  @FunctionalInterface
  private interface Task<T> {
    T run() throws Exception;
  }

  /** 저장소·입력 정책·변경 알림을 주입합니다. 알림 콜백은 DB 작업 스레드에서 호출됩니다. */
  public DefaultNicknameService(
      MariaRepository repository, NamePolicy policy, Consumer<NicknameProfile> changed) {
    this.repository = repository;
    this.policy = policy;
    this.changed = changed;
  }

  /** SQL 예외를 요청 종류별로 번역한 뒤 CompletableFuture 실패 결과로 반환합니다. */
  private <T> CompletableFuture<T> async(Task<T> task) {
    try {
      return CompletableFuture.supplyAsync(
          () -> {
            try {
              return task.run();
            } catch (SQLException error) {
              throw new CompletionException(SqlErrorTranslator.translate("닉네임 저장소 작업", error));
            } catch (Exception error) {
              throw new CompletionException(error);
            }
          },
          executor);
    } catch (RejectedExecutionException e) {
      return CompletableFuture.failedFuture(new IllegalStateException("요청이 많습니다. 잠시 후 다시 시도하세요."));
    }
  }

  /** 더 낮은 revision의 비동기 결과가 최신 캐시를 덮어쓰지 않도록 보장합니다. */
  private NicknameProfile committed(NicknameProfile profile) {
    NicknameProfile latest = cache.accept(profile);
    // 表示 콜백 실패는 이미 commit된 DB 변경을 실패로 바꾸지 않는다.
    try {
      changed.accept(latest);
    } catch (RuntimeException error) {
      System.getLogger(DefaultNicknameService.class.getName()).log(
          System.Logger.Level.ERROR, "DB 저장 후 표시 알림 실패", error);
    }
    return latest;
  }

  /** UUID 캐시가 있으면 즉시 반환하고 없으면 DB에서 읽어 캐시와 변경 알림에 반영합니다. */
  @Override
  public CompletableFuture<Optional<NicknameProfile>> findById(UUID id) {
    NicknameProfile hit = cache.find(id).orElse(null);
    if (hit != null) return CompletableFuture.completedFuture(Optional.of(hit));
    return async(
        () -> {
          Optional<NicknameProfile> found = repository.find("player_uuid", id.toString());
          found.ifPresent(this::committed);
          return found;
        });
  }

  /** 이전 이름의 잘못된 역매핑을 피하도록 매번 DB의 정규화 닉네임 인덱스를 조회합니다. */
  @Override
  public CompletableFuture<Optional<NicknameProfile>> findByNickname(String name) {
    return async(() -> repository.find("nickname_key", NamePolicy.key(name)));
  }

  /** 정규화 계정명을 DB로 조회합니다. 중복된 과거 계정명은 저장소가 UUID 조회를 요구합니다. */
  @Override
  public CompletableFuture<Optional<NicknameProfile>> findByAccountName(String name) {
    return async(() -> repository.find("account_name_key", NamePolicy.key(name)));
  }

  /** DB 작업 스레드에서 접속 계정명을 동기화하고 확정된 revision을 캐시에 반영합니다. */
  @Override
  public CompletableFuture<NicknameProfile> synchronizeAccount(UUID id, String name) {
    return async(() -> committed(repository.synchronize(id, name)));
  }

  /** 닉네임 검증 후 저장소 트랜잭션을 실행합니다. 검증 실패는 티켓을 사용 처리하지 않습니다. */
  @Override
  public CompletableFuture<NicknameProfile> changeWithTicket(
      UUID id, String name, UUID token, UUID request) {
    return async(
        () -> committed(repository.changeWithTicket(id, policy.validate(name), token, request)));
  }

  /** 입력 정책은 일반 변경과 같고 변경권만 생략합니다. 권한 검사는 플랫폼 호출자의 책임입니다. */
  @Override
  public CompletableFuture<NicknameProfile> forceChange(
      UUID id, String name, String actorType, String actorId, String reason, UUID request) {
    return async(
        () ->
            committed(
                repository.forceChange(
                    id, policy.validate(name), actorType, actorId, reason, request)));
  }

  /** 페이지 조회를 비동기 실행합니다. 반환 순서와 페이지 크기는 저장소 계약을 따릅니다. */
  @Override
  public CompletableFuture<List<HistoryEntry>> history(UUID id, int page) {
    return async(() -> repository.history(id, page));
  }

  /** 발급 DB 쓰기를 비동기 실행합니다. 아이템 생성·전달 확인은 플랫폼 책임입니다. */
  @Override
  public CompletableFuture<TicketIssue> issueTicket(String type, String actor, UUID target) {
    return async(() -> repository.issueTicket(type, actor, target));
  }

  /** DB 상태 조회를 비동기 실행하며 장애는 실패한 future로 전달합니다. */
  @Override
  public CompletableFuture<Map<UUID, TicketStatus>> ticketStatuses(List<UUID> tokens) {
    return async(() -> repository.ticketStatuses(tokens));
  }

  /** placeholder의 빠른 조회를 위한 메모리 전용 접근입니다. DB I/O를 수행하지 않습니다. */
  @Override
  public Optional<NicknameProfile> cached(UUID id) {
    return cache.find(id);
  }

  /** 새 작업을 막고 최대 10초 기다린 뒤 executor와 DB 풀을 닫습니다. 인터럽트 상태를 복원합니다. */
  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    repository.close();
    cache.clear();
  }
}
