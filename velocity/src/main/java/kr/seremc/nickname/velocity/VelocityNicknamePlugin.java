package kr.seremc.nickname.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.zaxxer.hikari.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import kr.seremc.nickname.api.*;
import kr.seremc.nickname.protocol.NicknameProtocol;
import kr.seremc.nickname.protocol.NicknameProtocol.Packet;
import kr.seremc.nickname.service.*;
import kr.seremc.nickname.storage.MariaRepository;
import kr.seremc.nickname.storage.error.DatabaseAuthenticationException;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(
    id = "customnickname",
    name = "CustomNickname",
    version = "2.0.0",
    authors = {"Seremc"},
    dependencies = {@Dependency(id = "tab", optional = true)})
/** 네트워크 전체의 닉네임 원본 서비스입니다. MariaDB와 변경권 판정은 이 Velocity 모듈에서만 수행하고 Paper에는 결과만 전파합니다. */
public final class VelocityNicknamePlugin {
  static final MinecraftChannelIdentifier CHANNEL =
      MinecraftChannelIdentifier.from(NicknameProtocol.CHANNEL);
  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;
  private volatile DefaultNicknameService names;
  private volatile TabIntegration tab;
  private volatile boolean stopping;
  private volatile boolean ready;
  private final kr.seremc.nickname.protocol.PendingChanges pending = new kr.seremc.nickname.protocol.PendingChanges();

  /** Velocity가 주입한 프록시, 로거, 전용 설정 디렉터리를 보존합니다. */
  @Inject
  public VelocityNicknamePlugin(
      ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  /**
   * DB 풀과 스키마를 준비한 뒤 서비스·채널·명령어·TAB을 연결합니다. 시작 실패는 로그와 예외로 전달합니다. 서버 재시작이나 비밀번호 설정은 자동 수행하지 않습니다.
   */
  @Subscribe
  public void onInitialize(ProxyInitializeEvent event) {
    HikariDataSource pool = null;
    try {
      VelocityConfig config = VelocityConfig.load(dataDirectory);
      String databasePassword = config.secret("database.password-env", "database.password");
      if (databasePassword.isBlank())
        throw new DatabaseAuthenticationException("Velocity 시작 시 DB 연결", "", 0, null);
      HikariConfig hikari = new HikariConfig();
      hikari.setJdbcUrl(config.get("database.url"));
      hikari.setUsername(config.get("database.username"));
      hikari.setPassword(databasePassword);
      hikari.setDriverClassName("org.mariadb.jdbc.Driver");
      hikari.setMaximumPoolSize(Math.max(2, config.integer("database.pool-size", 6)));
      hikari.setConnectionTimeout(3000);
      hikari.setPoolName("CustomNicknameVelocity");
      pool = new HikariDataSource(hikari);
      MariaRepository repository = new MariaRepository(pool);
      repository.initialize();
      names =
          new DefaultNicknameService(
              repository,
              new NamePolicy(
                  config.integer("nickname.min-length", 2),
                  config.integer("nickname.max-length", 16),
                  config.list("nickname.blocked")),
              this::profileChanged);
      proxy.getChannelRegistrar().register(CHANNEL);
      new VelocityCommands(this, proxy, names, logger).register();
      if (proxy.getPluginManager().isLoaded("tab")) {
        tab = new TabIntegration(names, logger, config.integer("tab.refresh-milliseconds", 1000));
        tab.initialize();
      }
      ready = true;
      logger.info("CustomNickname Velocity가 시작되었습니다.");
    } catch (Exception e) {
      ready = false;
      if (names != null) { names.close(); names = null; }
      else if (pool != null) pool.close();
      logger.error("CustomNickname Velocity 시작 실패", e);
      throw new IllegalStateException(e);
    }
  }

  /**
   * 접속 계정 동기화 완료까지 비동기 로그인 이벤트를 보류합니다. 동기화 실패 시 프로필이 불완전한 접속을 거절합니다. 현재 names가 null이면 동기화를 건너뜁니다.
   */
  @Subscribe
  public EventTask onLogin(LoginEvent event) {
    if (!ready || stopping) {
      event.setResult(com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(
          Component.text("닉네임 서비스를 준비하지 못했습니다. 관리자에게 문의해 주세요.")));
      return null;
    }
    CompletableFuture<?> future =
        names
            .synchronizeAccount(event.getPlayer().getUniqueId(), event.getPlayer().getUsername())
            .exceptionally(
                error -> {
                  logger.warn("닉네임 접속 동기화 실패: {}", event.getPlayer().getUsername(), unwrap(error));
                  event.setResult(
                      com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(
                          Component.text("닉네임 정보를 불러오지 못했습니다. 잠시 후 다시 접속해 주세요.")));
                  return null;
                });
    return EventTask.resumeWhenComplete(future);
  }

  /** 서버 이동 후 현재 프로필을 새 backend에 보냅니다. Paper ready 요청도 초기 메시지 유실을 보완합니다. */
  @Subscribe
  public void onServerConnected(ServerPostConnectEvent event) {
    pending.clear(event.getPlayer().getUniqueId());
    if (!ready || stopping) return;
    UUID id = event.getPlayer().getUniqueId();
    names
        .findById(id)
        .thenAccept(found -> found.ifPresent(profile -> sendProfile(event.getPlayer(), profile)))
        .exceptionally(this::logFailure);
  }

  /**
   * 전용 채널을 먼저 handled 처리한 뒤 backend 출처·UUID·현재 서버 연결을 검증합니다. 클라이언트의 직접 메시지와 이전 backend 메시지는 서비스 호출
   * 전에 거절합니다.
   */
  @Subscribe
  public void onPluginMessage(PluginMessageEvent event) {
    if (!CHANNEL.equals(event.getIdentifier())) return;
    event.setResult(PluginMessageEvent.ForwardResult.handled());
    if (!ready || stopping) return;
    if (!(event.getSource() instanceof ServerConnection backend)) return;
    Packet packet;
    try {
      packet = NicknameProtocol.decode(event.getData());
    } catch (IllegalArgumentException e) {
      logger.warn("손상된 닉네임 메시지: {}", e.getMessage());
      return;
    }
    Player player = backend.getPlayer();
    if (!packet.playerId().equals(player.getUniqueId())) {
      logger.warn("닉네임 메시지 UUID 위조 차단: {}", player.getUsername());
      return;
    }
    if (player.getCurrentServer().isEmpty() || player.getCurrentServer().get() != backend) {
      logger.warn("이전 서버의 닉네임 메시지 차단: {}", player.getUsername());
      return;
    }
    switch (packet.type()) {
      case PROFILE_REQUEST, BRIDGE_READY ->
          names
              .findById(player.getUniqueId())
              .thenAccept(found -> found.ifPresent(p -> sendProfile(player, p)))
              .exceptionally(this::logFailure);
      case TICKET_USE -> {
        if (packet.fields().size() == 2 && pending.consume(player.getUniqueId(), packet.requestId(), packet.field(1), backend))
          handleTicketUse(player, packet);
      }
      case TICKET_INVENTORY_STATE -> handleInventoryState(player, packet);
      default -> logger.warn("백엔드가 보낼 수 없는 메시지 차단: {}", packet.type());
    }
  }

  /**
   * Paper가 확인한 손 아이템 UUID와 새 닉네임을 서비스에 전달합니다. commit 성공 뒤 제거·결과·프로필을 보내며, 유효성 거절만 제거를 허용하고 DB 장애에서는
   * 보존합니다.
   */
  private void handleTicketUse(Player player, Packet packet) {
    try {
      UUID token = UUID.fromString(packet.field(0));
      String nickname = packet.field(1);
      names
          .changeWithTicket(player.getUniqueId(), nickname, token, packet.requestId())
          .whenComplete(
              (profile, error) -> {
                if (error == null) {
                  sendProfile(player, profile);
                  send(
                      player,
                      NicknameProtocol.Type.REMOVE_TICKET_ID,
                      packet.requestId(),
                      token.toString());
                  send(
                      player,
                      NicknameProtocol.Type.NICKNAME_RESULT,
                      packet.requestId(),
                      "SUCCESS",
                      profile.nickname());
                  return;
                }
                Throwable cause = unwrap(error);
                if (!(cause instanceof IllegalArgumentException)) logger.warn("닉네임 변경 실패", cause);
                if (cause instanceof TicketRejectedException rejected && rejected.removeItem())
                  send(
                      player,
                      NicknameProtocol.Type.REMOVE_TICKET_ID,
                      packet.requestId(),
                      token.toString());
                send(
                    player,
                    NicknameProtocol.Type.NICKNAME_RESULT,
                    packet.requestId(),
                    "ERROR",
                    safeMessage(cause));
              });
    } catch (IllegalArgumentException e) {
      send(
          player,
          NicknameProtocol.Type.NICKNAME_RESULT,
          packet.requestId(),
          "ERROR",
          e.getMessage());
    }
  }

  /** 보유 토큰을 DB와 대조하고 무효 ID를 최대 30개씩 반환합니다. DB 실패는 로그만 남기므로 조회 장애로 정상 아이템을 제거하지 않습니다. */
  private void handleInventoryState(Player player, Packet packet) {
    List<UUID> tokens = new ArrayList<>();
    for (String field : packet.fields())
      try {
        tokens.add(UUID.fromString(field));
      } catch (IllegalArgumentException ignored) {
      }
    names
        .ticketStatuses(tokens)
        .thenAccept(
            states -> {
              List<String> invalid =
                  states.entrySet().stream()
                      .filter(e -> !e.getValue().usable())
                      .map(e -> e.getKey().toString())
                      .toList();
              for (int from = 0; from < invalid.size(); from += 30) {
                List<String> chunk = invalid.subList(from, Math.min(from + 30, invalid.size()));
                send(
                    player,
                    NicknameProtocol.Type.TICKET_RECONCILE_RESULT,
                    packet.requestId(),
                    chunk.toArray(String[]::new));
              }
            })
        .exceptionally(this::logFailure);
  }

  /** 프록시는 인벤토리를 볼 수 없으므로 현재 Paper 서버에 손 아이템 검사를 요청합니다. */
  void requestTicketUse(Player player, String nickname) {
    if (!ready || stopping) throw new IllegalArgumentException("닉네임 서비스가 준비되지 않았습니다.");
    ServerConnection connection = player.getCurrentServer().orElseThrow(() -> new IllegalArgumentException("서버 연결을 기다려 주세요."));
    UUID request = UUID.randomUUID();
    if (!pending.issue(player.getUniqueId(), request, nickname, connection))
      throw new IllegalArgumentException("이전 요청 확인 중입니다. 최대 15초 후 다시 시도해 주세요.");
    if (!connection.sendPluginMessage(CHANNEL, NicknameProtocol.encode(NicknameProtocol.Type.TICKET_USE_REQUEST, request, player.getUniqueId(), nickname))) {
      pending.clear(player.getUniqueId());
      throw new IllegalArgumentException("서버에 변경 요청을 전달하지 못했습니다.");
    }
  }

  /** 연결이 끝난 플레이어의 미완료 요청을 보관하지 않습니다. */
  @Subscribe public void onDisconnect(DisconnectEvent event) { pending.clear(event.getPlayer().getUniqueId()); }

  /** 발급 UUID를 현재 Paper 서버로 보냅니다. 전송은 아이템 전달 완료 확인 응답을 기다리지 않습니다. */
  void deliverTicket(Player player, TicketIssue issue) {
    send(
        player,
        NicknameProtocol.Type.TICKET_ITEM_CREATE,
        UUID.randomUUID(),
        issue.token().toString());
  }

  /** 서비스 DB 작업 스레드의 알림을 프록시 스케줄러로 넘겨 TAB과 현재 backend를 갱신합니다. */
  private void profileChanged(NicknameProfile profile) {
    if (stopping) return;
    proxy
        .getScheduler()
        .buildTask(
            this,
            () -> {
              if (tab != null) tab.refresh(profile);
              proxy.getPlayer(profile.playerId()).ifPresent(player -> sendProfile(player, profile));
            })
        .schedule();
  }

  /** TAB 값을 먼저 적용하고 계정명·닉네임·custom·revision 순서로 Paper에 전송합니다. */
  private void sendProfile(Player player, NicknameProfile p) {
    p = names.cached(p.playerId()).orElse(p);
    if (tab != null) tab.refresh(p);
    send(
        player,
        NicknameProtocol.Type.PROFILE_SYNC,
        UUID.randomUUID(),
        p.accountName(),
        p.nickname(),
        Boolean.toString(p.custom()),
        Long.toString(p.revision()));
  }

  /** 플레이어의 현재 서버 연결을 사용합니다. 연결이 없으면 메시지를 보관하거나 재시도하지 않습니다. */
  private void send(Player player, NicknameProtocol.Type type, UUID request, String... fields) {
    player
        .getCurrentServer()
        .ifPresent(
            connection ->
                connection.sendPluginMessage(
                    CHANNEL, NicknameProtocol.encode(type, request, player.getUniqueId(), fields)));
  }

  /** 비동기 예외의 래퍼를 벗겨 운영 로그에 기록합니다. exceptionally 콜백용 null을 반환합니다. */
  private Void logFailure(Throwable error) {
    logger.warn("닉네임 비동기 처리 실패", unwrap(error));
    return null;
  }

  /** CompletionException과 ExecutionException을 반복해서 해제해 실제 원인을 반환합니다. */
  static Throwable unwrap(Throwable error) {
    Throwable e = error;
    while ((e instanceof CompletionException || e instanceof ExecutionException)
        && e.getCause() != null) e = e.getCause();
    return e;
  }

  /** 현재 예외 메시지를 사용자에게 반환합니다. SQL 경로는 SqlErrorTranslator의 안전한 문구를 사용해야 합니다. */
  static String safeMessage(Throwable error) {
    if (!(error instanceof IllegalArgumentException)
        && !(error instanceof kr.seremc.nickname.storage.error.DatabaseException))
      return "닉네임 요청을 처리하지 못했습니다. 관리자에게 문의해 주세요.";
    String message = error.getMessage();
    return message == null || message.isBlank() ? "닉네임 요청을 처리하지 못했습니다." : message;
  }

  /** 추가 표시 알림을 중단하고 진행 중 DB 작업 종료를 기다린 뒤 풀과 캐시를 닫습니다. */
  @Subscribe
  public void onShutdown(ProxyShutdownEvent event) {
    stopping = true;
    ready = false;
    if (names != null) names.close();
  }
}
