package kr.seremc.nickname.paper;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import kr.seremc.nickname.api.NicknameProfile;
import org.bukkit.Bukkit;

/**
 * CustomNameplates가 선택적으로 설치된 경우에만 placeholder를 등록하는 어댑터입니다. 리플렉션을 사용해 브리지 JAR가 CustomNameplates
 * API를 함께 포함하거나 강제 의존하지 않게 합니다.
 */
final class CustomNameplatesIntegration {
  /**
   * CustomNameplates 3.x API는 등록·조회·해제에 모두 완전한 placeholder 표기법을 요구한다.
   * 설정 파일에서 사용하는 표기와 같은 값을 유지하면 reload 뒤의 이전 등록 해제도 같은 키로 수행된다.
   */
  private static final String PLACEHOLDER = "%customnickname_nickname%";
  private final PaperNicknameBridge bridge;
  private Object subscription;

  /** 캐시 조회와 서버 스케줄러를 제공하는 브리지를 주입합니다. */
  CustomNameplatesIntegration(PaperNicknameBridge bridge) {
    this.bridge = bridge;
  }

  /**
   * 선택 의존성이 활성화된 경우 등록과 reload 이벤트 구독을 시작합니다. 이벤트는 다음 Bukkit tick에서 재등록하여 reload 처리와 직접 겹치지 않게 합니다.
   */
  void initialize() {
    if (!Bukkit.getPluginManager().isPluginEnabled("CustomNameplates")) return;
    try {
      register();
      Class<?> api = Class.forName("net.momirealms.customnameplates.api.CustomNameplates");
      Object plugin = api.getMethod("getInstance").invoke(null);
      Object manager = api.getMethod("getEventManager").invoke(plugin);
      Class<?> event =
          Class.forName("net.momirealms.customnameplates.api.event.NameplatesReloadEvent");
      Class<?> subscriber =
          Class.forName("net.momirealms.customnameplates.common.event.EventSubscriber");
      Object callback =
          Proxy.newProxyInstance(
              subscriber.getClassLoader(),
              new Class<?>[] {subscriber},
              (p, method, args) -> {
                if (method.getName().equals("onEvent"))
                  Bukkit.getScheduler().runTask(bridge, this::register);
                return null;
              });
      subscription =
          manager
              .getClass()
              .getMethod("subscribe", Class.class, subscriber)
              .invoke(manager, event, callback);
    } catch (ReflectiveOperationException e) {
      bridge.getLogger().warning("CustomNameplates 연동 실패: " + e.getMessage());
    }
  }

  /** reload 뒤에도 마지막 캐시 값을 반환하는 placeholder를 다시 등록합니다. */
  private void register() {
    try {
      Class<?> api = Class.forName("net.momirealms.customnameplates.api.CustomNameplates");
      Object plugin = api.getMethod("getInstance").invoke(null);
      Object manager = api.getMethod("getPlaceholderManager").invoke(plugin);
      Class<?> managerType =
          Class.forName("net.momirealms.customnameplates.api.placeholder.PlaceholderManager");
      // 등록된 placeholder가 있을 때만 제거한다. 없는 ID를 제거할 때 발생하던 CustomNameplates 내부 NPE를 피한다.
      Object previous =
          managerType
              .getMethod("getRegisteredPlaceholder", String.class)
              .invoke(manager, PLACEHOLDER);
      if (previous != null)
        managerType.getMethod("unregisterPlaceholder", String.class).invoke(manager, PLACEHOLDER);
      Function<Object, String> value =
          player -> {
            try {
              UUID id = (UUID) player.getClass().getMethod("uuid").invoke(player);
              return bridge
                  .profile(id)
                  .map(NicknameProfile::nickname)
                  .orElseGet(
                      () -> {
                        try {
                          return (String) player.getClass().getMethod("name").invoke(player);
                        } catch (ReflectiveOperationException ignored) {
                          return "";
                        }
                      });
            } catch (ReflectiveOperationException e) {
              return "";
            }
          };
      int refresh = Math.max(20, bridge.getConfig().getInt("custom-nameplates.refresh-ticks", 20));
      managerType
          .getMethod("registerPlayerPlaceholder", String.class, int.class, Function.class)
          .invoke(manager, PLACEHOLDER, refresh, value);
      Bukkit.getOnlinePlayers().forEach(player -> refresh(player.getUniqueId()));
      bridge.getLogger().info("CustomNameplates placeholder를 등록했습니다: " + PLACEHOLDER);
    } catch (ReflectiveOperationException e) {
      Throwable cause =
          e instanceof InvocationTargetException invocation && invocation.getCause() != null
              ? invocation.getCause()
              : e;
      bridge
          .getLogger()
          .log(
              java.util.logging.Level.WARNING,
              "CustomNameplates placeholder 등록 실패: " + cause,
              cause);
    }
  }

  /**
   * DB 변경 이후 해당 CNPlayer의 placeholder를 한 번 강제 갱신합니다. CNPlayer가 아직 준비되지 않았으면 반환하며 반사 호출 실패는 현재 구현에서
   * 무시됩니다.
   */
  void refresh(UUID id) {
    try {
      Class<?> apiType = Class.forName("net.momirealms.customnameplates.api.CustomNameplatesAPI");
      Object api = apiType.getMethod("getInstance").invoke(null);
      Object player = apiType.getMethod("getPlayer", UUID.class).invoke(api, id);
      if (player == null) return;
      Object plugin = apiType.getMethod("plugin").invoke(api);
      Object manager = plugin.getClass().getMethod("getPlaceholderManager").invoke(plugin);
      Object placeholder =
          manager
              .getClass()
              .getMethod("getRegisteredPlaceholder", String.class)
              .invoke(manager, PLACEHOLDER);
      if (placeholder != null) {
        Method force =
            Arrays.stream(player.getClass().getMethods())
                .filter(m -> m.getName().equals("forceUpdatePlaceholders"))
                .findFirst()
                .orElseThrow();
        force.invoke(player, Set.of(placeholder), List.of(player));
      }
    } catch (ReflectiveOperationException | NoSuchElementException ignored) {
    }
  }

  /** reload 이벤트 구독을 해제합니다. 선택 플러그인 종료 시 반사 호출 실패는 무시합니다. */
  void close() {
    if (subscription == null) return;
    try {
      subscription.getClass().getMethod("dispose").invoke(subscription);
    } catch (ReflectiveOperationException ignored) {
    }
  }
}
