package kr.seremc.nickname.velocity;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.proxy.*;
import java.util.*;
import java.util.concurrent.*;
import kr.seremc.nickname.api.*;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * 플레이어 조회·변경과 관리자 지급·강제 변경·이력 명령을 Velocity에서 한 번만 등록합니다. 명령 처리 결과는 비동기 서비스에서 오므로 Bukkit/Paper 객체를 이
 * 클래스에 추가하지 않습니다.
 */
final class VelocityCommands implements SimpleCommand {
  private final VelocityNicknamePlugin plugin;
  private final ProxyServer proxy;
  private final NicknameService names;
  private final Logger logger;
  private boolean admin;

  /** 플랫폼 경계에서 필요한 서비스와 메시지 전달자를 주입합니다. 관리자 여부는 등록 때 결정됩니다. */
  VelocityCommands(
      VelocityNicknamePlugin plugin, ProxyServer proxy, NicknameService names, Logger logger) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.names = names;
    this.logger = logger;
  }

  /** 일반 명령과 관리자 명령을 서로 다른 인스턴스로 등록하여 권한 판정을 구분합니다. */
  void register() {
    var userMeta =
        proxy
            .getCommandManager()
            .metaBuilder("nickname")
            .aliases("nick", "닉네임")
            .plugin(plugin)
            .build();
    proxy.getCommandManager().register(userMeta, this);
    VelocityCommands adminCommand = new VelocityCommands(plugin, proxy, names, logger);
    adminCommand.admin = true;
    var adminMeta =
        proxy.getCommandManager().metaBuilder("nickadmin").aliases("닉관리").plugin(plugin).build();
    proxy.getCommandManager().register(adminMeta, adminCommand);
  }

  /** 명령 종류를 분기하고 동기 입력 오류는 사용자에게 안내합니다. DB 실패는 finish에서 처리합니다. */
  @Override
  public void execute(Invocation invocation) {
    try {
      if (admin) admin(invocation);
      else user(invocation);
    } catch (IllegalArgumentException e) {
      tell(invocation.source(), e.getMessage());
    }
  }

  /** 조회는 서비스 API로, 변경은 Paper 손 아이템 확인으로 연결합니다. 콘솔의 자기 조회는 허용하지 않습니다. */
  private void user(Invocation in) {
    String[] a = in.arguments();
    if (a.length == 0) {
      tell(in.source(), "/닉네임 조회 [닉네임|@계정명|UUID] | /닉네임 변경 <새이름>");
      return;
    }
    if (eq(a[0], "조회", "lookup")) {
      if (a.length == 1 && in.source() instanceof Player p)
        finish(
            in.source(),
            names
                .findById(p.getUniqueId())
                .thenApply(v -> v.orElseThrow(() -> new IllegalArgumentException("프로필이 없습니다."))),
            profile -> show(in.source(), profile));
      else if (a.length == 2)
        finish(in.source(), resolve(a[1]), profile -> show(in.source(), profile));
      else throw new IllegalArgumentException("/닉네임 조회 [대상]");
      return;
    }
    if (eq(a[0], "변경", "change")) {
      if (!(in.source() instanceof Player p))
        throw new IllegalArgumentException("플레이어만 사용할 수 있습니다.");
      if (a.length != 2) throw new IllegalArgumentException("/닉네임 변경 <새이름>");
      plugin.requestTicketUse(p, a[1]);
      tell(p, "변경권을 확인하고 있습니다.");
      return;
    }
    throw new IllegalArgumentException("/닉네임 조회 또는 /닉네임 변경을 사용하세요.");
  }

  /** 관리 권한 확인 후 지급·강제 변경·페이지 조회를 수행합니다. 강제 변경은 실행자 UUID 또는 CONSOLE과 입력 사유를 감사 기록에 전달합니다. */
  private void admin(Invocation in) {
    if (!in.source().hasPermission("customnickname.admin")) {
      tell(in.source(), "권한이 없습니다.");
      return;
    }
    String[] a = in.arguments();
    if (a.length == 0) {
      tell(in.source(), "/닉관리 지급 <온라인계정명> | 강제변경 <대상> <새이름> <사유> | 기록 <대상> [페이지]");
      return;
    }
    String actor = in.source() instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    if (eq(a[0], "지급", "give")) {
      if (a.length != 2) throw new IllegalArgumentException("/닉관리 지급 <온라인계정명>");
      Player target =
          proxy.getPlayer(a[1]).orElseThrow(() -> new IllegalArgumentException("온라인 플레이어가 아닙니다."));
      finish(
          in.source(),
          names.issueTicket("ADMIN", actor, target.getUniqueId()),
          issue -> {
            plugin.deliverTicket(target, issue);
            tell(in.source(), "변경권을 지급했습니다.");
          });
      return;
    }
    if (eq(a[0], "강제변경", "set")) {
      if (a.length < 4) throw new IllegalArgumentException("/닉관리 강제변경 <대상> <새이름> <사유>");
      String reason = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
      finish(
          in.source(),
          resolve(a[1])
              .thenCompose(
                  p ->
                      names.forceChange(
                          p.playerId(), a[2], "ADMIN", actor, reason, UUID.randomUUID())),
          p -> tell(in.source(), p.accountName() + " → " + p.nickname()));
      return;
    }
    if (eq(a[0], "기록", "history")) {
      if (a.length < 2 || a.length > 3) throw new IllegalArgumentException("/닉관리 기록 <대상> [페이지]");
      int page = a.length == 3 ? Integer.parseInt(a[2]) : 1;
      finish(
          in.source(),
          resolve(a[1]).thenCompose(p -> names.history(p.playerId(), page)),
          entries -> {
            tell(in.source(), "닉네임 기록 " + page + "페이지");
            for (HistoryEntry e : entries)
              tell(
                  in.source(),
                  "#"
                      + e.id()
                      + " "
                      + e.oldName()
                      + " → "
                      + e.newName()
                      + " | "
                      + e.actorType()
                      + ":"
                      + e.actorId()
                      + " | "
                      + e.reason());
          });
      return;
    }
    throw new IllegalArgumentException("/닉관리 지급 | 강제변경 | 기록");
  }

  /** UUID 문자열, @계정명, 표시 닉네임 순으로 조회 종류를 결정합니다. 결과 없음은 입력 오류로 처리합니다. */
  private CompletableFuture<NicknameProfile> resolve(String value) {
    UUID id = null;
    try {
      id = UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
    }
    CompletableFuture<Optional<NicknameProfile>> result =
        id != null
            ? names.findById(id)
            : value.startsWith("@")
                ? names.findByAccountName(value.substring(1))
                : names.findByNickname(value);
    return result.thenApply(
        v -> v.orElseThrow(() -> new IllegalArgumentException("플레이어를 찾을 수 없습니다.")));
  }

  /** 사용자가 서로 다른 식별자를 혼동하지 않게 닉네임·계정명·UUID를 함께 출력합니다. */
  private void show(CommandSource source, NicknameProfile p) {
    tell(source, p.nickname() + " / 계정명: " + p.accountName() + " / UUID: " + p.playerId());
  }

  /** 비동기 결과를 출력하고 시스템 실패는 로그에 남깁니다. 호출 스레드를 메인 스레드로 전환하지 않습니다. */
  private <T> void finish(
      CommandSource source, CompletableFuture<T> future, java.util.function.Consumer<T> success) {
    future.whenComplete(
        (value, error) -> {
          if (error == null) success.accept(value);
          else {
            Throwable cause = VelocityNicknamePlugin.unwrap(error);
            if (!(cause instanceof IllegalArgumentException)) logger.warn("명령 처리 실패", cause);
            tell(source, VelocityNicknamePlugin.safeMessage(cause));
          }
        });
  }

  /** 한국어 하위 명령과 영문 별칭을 대소문자 무시로 비교합니다. */
  private static boolean eq(String input, String a, String b) {
    return input.equalsIgnoreCase(a) || input.equalsIgnoreCase(b);
  }

  /** 모든 명령 응답에 동일한 접두사를 붙이며 색상 태그를 해석하지 않는 텍스트로 출력합니다. */
  private static void tell(CommandSource source, String text) {
    source.sendMessage(Component.text("[닉네임] " + text));
  }

  /** Velocity 명령 노출 단계에서 관리자 권한을 검사합니다. 실행 시에도 동일 권한을 확인합니다. */
  @Override
  public boolean hasPermission(Invocation invocation) {
    return !admin || invocation.source().hasPermission("customnickname.admin");
  }

  /** 첫 번째 인자에 대해서만 하위 명령 후보를 제시합니다. 플레이어 이름 자동 완성은 구현하지 않습니다. */
  @Override
  public List<String> suggest(Invocation invocation) {
    if (invocation.arguments().length != 1) return List.of();
    return (admin ? List.of("지급", "강제변경", "기록") : List.of("조회", "변경"))
        .stream().filter(s -> s.startsWith(invocation.arguments()[0])).toList();
  }
}
