package kr.seremc.nickname.paper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import kr.seremc.nickname.api.NicknameProfile;
import kr.seremc.nickname.protocol.NicknameProtocol;
import kr.seremc.nickname.protocol.NicknameProtocol.Packet;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * Velocity가 확정한 프로필을 Paper 화면과 인벤토리에 적용하는 브리지입니다. 이 모듈은 MariaDB에 연결하지 않으며, 모든 신뢰 판단은 Velocity의 응답을
 * 기준으로 합니다.
 */
public final class PaperNicknameBridge extends JavaPlugin
    implements Listener, PluginMessageListener {
  private final Map<UUID, NicknameProfile> profiles = new ConcurrentHashMap<>();
  private TicketItems tickets;
  private CustomNameplatesIntegration nameplates;

  /** 기본 설정·PDC 도구·메시지 채널·리스너·nameplates 어댑터를 연결하고 온라인 플레이어 상태를 요청합니다. */
  @Override
  public void onEnable() {
    saveDefaultConfig();
    tickets = new TicketItems(this);
    getServer().getMessenger().registerIncomingPluginChannel(this, NicknameProtocol.CHANNEL, this);
    getServer().getMessenger().registerOutgoingPluginChannel(this, NicknameProtocol.CHANNEL);
    getServer().getPluginManager().registerEvents(this, this);
    nameplates = new CustomNameplatesIntegration(this);
    nameplates.initialize();
    Bukkit.getOnlinePlayers().forEach(this::requestState);
  }

  /** 선택 연동의 이벤트 구독을 종료하고 로컬 프로필 캐시를 비웁니다. */
  @Override
  public void onDisable() {
    if (nameplates != null) nameplates.close();
    profiles.clear();
  }

  /** 현재 서버가 마지막으로 수신한 프로필을 반환합니다. 오프라인 전역 DB 조회 API가 아닙니다. */
  public Optional<NicknameProfile> profile(UUID id) {
    return Optional.ofNullable(profiles.get(id));
  }

  /** 플레이어 연결이 준비되도록 한 tick 뒤 프로필과 티켓 대조를 요청합니다. */
  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Bukkit.getScheduler().runTaskLater(this, () -> requestState(event.getPlayer()), 1L);
  }

  /** 접속·서버 이동 직후 프로필과 보유 변경권 상태를 Velocity에 다시 요청합니다. */
  private void requestState(Player player) {
    if (!player.isOnline()) return;
    UUID request = UUID.randomUUID();
    send(player, NicknameProtocol.Type.BRIDGE_READY, request);
    send(player, NicknameProtocol.Type.PROFILE_REQUEST, request);
    List<String> ids = tickets.ids(player).stream().map(UUID::toString).toList();
    for (int from = 0; from < ids.size(); from += 30) {
      List<String> chunk = ids.subList(from, Math.min(from + 30, ids.size()));
      send(
          player,
          NicknameProtocol.Type.TICKET_INVENTORY_STATE,
          request,
          chunk.toArray(String[]::new));
    }
  }

  /**
   * 채널·패킷 형식·운반 플레이어 UUID를 확인한 뒤 허용된 프록시 메시지만 처리합니다. 정상 프록시를 통한 backend 접속 제한은 서버 설정에서 보장해야 합니다.
   */
  @Override
  public void onPluginMessageReceived(
      @NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
    if (!NicknameProtocol.CHANNEL.equals(channel)) return;
    Packet packet;
    try {
      packet = NicknameProtocol.decode(message);
    } catch (IllegalArgumentException e) {
      getLogger().warning("손상된 프록시 메시지: " + e.getMessage());
      return;
    }
    if (!packet.playerId().equals(player.getUniqueId())) {
      getLogger().warning("플레이어 UUID가 다른 프록시 메시지를 차단했습니다.");
      return;
    }
    try {
      switch (packet.type()) {
        case PROFILE_SYNC -> applyProfile(player, packet);
        case TICKET_ITEM_CREATE -> createTicket(player, packet);
        case TICKET_USE_REQUEST -> useTicket(player, packet);
        case REMOVE_TICKET_ID -> removeTicket(player, UUID.fromString(packet.field(0)), true);
        case TICKET_RECONCILE_RESULT ->
            packet.fields().forEach(value -> removeTicket(player, UUID.fromString(value), false));
        case NICKNAME_RESULT -> showResult(player, packet);
        default -> getLogger().warning("프록시가 보낼 수 없는 메시지를 차단했습니다: " + packet.type());
      }
    } catch (IllegalArgumentException e) {
      player.sendMessage(Component.text("[닉네임] 잘못된 요청입니다: " + e.getMessage()));
    }
  }

  /** revision이 낮은 지연 packet은 무시해 서버 이동 중 이전 닉네임으로 되돌아가지 않게 합니다. */
  private void applyProfile(Player player, Packet packet) {
    NicknameProfile next =
        new NicknameProfile(
            player.getUniqueId(),
            packet.field(0),
            packet.field(1),
            Boolean.parseBoolean(packet.field(2)),
            Long.parseLong(packet.field(3)));
    NicknameProfile applied =
        profiles.compute(
            player.getUniqueId(),
            (id, old) -> old == null || next.revision() >= old.revision() ? next : old);
    if (applied != next) return;
    if (nameplates != null) nameplates.refresh(player.getUniqueId());
    player.displayName(Component.text(next.nickname()));
    getServer().getPluginManager().callEvent(new NicknameUpdatedEvent(next));
  }

  /** Velocity가 방금 발급한 UUID를 PDC가 들어 있는 변경권 아이템으로 변환합니다. */
  private void createTicket(Player player, Packet packet) {
    UUID id = UUID.fromString(packet.field(0));
    Map<Integer, org.bukkit.inventory.ItemStack> overflow =
        player.getInventory().addItem(tickets.create(id));
    if (overflow.isEmpty()) player.sendMessage(Component.text("[닉네임] 닉네임 변경권을 받았습니다."));
    else player.sendMessage(Component.text("[닉네임] 인벤토리에 빈칸이 없어 변경권을 받지 못했습니다. 관리자에게 다시 요청하세요."));
  }

  /** 주 손의 PDC UUID만 추출해 원래 requestId와 새 이름을 프록시에 전달합니다. 여기서는 아이템을 소비하지 않습니다. */
  private void useTicket(Player player, Packet packet) {
    tickets
        .id(player.getInventory().getItemInMainHand())
        .ifPresentOrElse(
            id ->
                send(
                    player,
                    NicknameProtocol.Type.TICKET_USE,
                    packet.requestId(),
                    id.toString(),
                    packet.field(0)),
            () -> player.sendMessage(Component.text("[닉네임] 주 손에 닉네임 변경권을 들어 주세요.")));
  }

  /** 동일 UUID의 복제본까지 현재 인벤토리·off-hand·cursor에서 모두 제거합니다. */
  private void removeTicket(Player player, UUID id, boolean used) {
    int count = tickets.removeAll(player, id);
    if (count > 0 && !used)
      player.sendMessage(Component.text("[닉네임] 사용할 수 없는 변경권 " + count + "개를 제거했습니다."));
  }

  /** DB 결과에 따른 성공 또는 거절 문구를 표시합니다. 모든 외부 표시가 완료되었다는 확인 응답은 아닙니다. */
  private void showResult(Player player, Packet packet) {
    boolean success = "SUCCESS".equals(packet.field(0));
    player.sendMessage(
        Component.text(
            success
                ? "[닉네임] 닉네임이 " + packet.field(1) + "(으)로 변경되었습니다."
                : "[닉네임] " + packet.field(1)));
  }

  /** 접속 플레이어를 운반자로 사용해 버전 있는 packet을 전송합니다. 오프라인 전송 큐는 없습니다. */
  private void send(Player player, NicknameProtocol.Type type, UUID request, String... fields) {
    player.sendPluginMessage(
        this,
        NicknameProtocol.CHANNEL,
        NicknameProtocol.encode(type, request, player.getUniqueId(), fields));
  }
}
