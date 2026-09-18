package kr.seremc.nickname.paper;

import kr.seremc.nickname.api.NicknameProfile;
import org.bukkit.Bukkit;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;

final class CustomNameplatesIntegration {
    private static final String PLACEHOLDER = "customnickname_nickname";
    private final PaperNicknameBridge bridge;
    private Object subscription;

    CustomNameplatesIntegration(PaperNicknameBridge bridge) { this.bridge = bridge; }

    void initialize() {
        if (!Bukkit.getPluginManager().isPluginEnabled("CustomNameplates")) return;
        try {
            register();
            Class<?> api = Class.forName("net.momirealms.customnameplates.api.CustomNameplates");
            Object plugin = api.getMethod("getInstance").invoke(null);
            Object manager = api.getMethod("getEventManager").invoke(plugin);
            Class<?> event = Class.forName("net.momirealms.customnameplates.api.event.NameplatesReloadEvent");
            Class<?> subscriber = Class.forName("net.momirealms.customnameplates.common.event.EventSubscriber");
            Object callback = Proxy.newProxyInstance(subscriber.getClassLoader(), new Class<?>[]{subscriber}, (p, method, args) -> {
                if (method.getName().equals("onEvent")) Bukkit.getScheduler().runTask(bridge, this::register);
                return null;
            });
            subscription = manager.getClass().getMethod("subscribe", Class.class, subscriber).invoke(manager, event, callback);
        } catch (ReflectiveOperationException e) {
            bridge.getLogger().warning("CustomNameplates 연동 실패: " + e.getMessage());
        }
    }

    private void register() {
        try {
            Class<?> api = Class.forName("net.momirealms.customnameplates.api.CustomNameplates");
            Object plugin = api.getMethod("getInstance").invoke(null);
            Object manager = api.getMethod("getPlaceholderManager").invoke(plugin);
            Class<?> managerType = Class.forName("net.momirealms.customnameplates.api.placeholder.PlaceholderManager");
            managerType.getMethod("unregisterPlaceholder", String.class).invoke(manager, PLACEHOLDER);
            Function<Object, String> value = player -> {
                try {
                    UUID id = (UUID) player.getClass().getMethod("uuid").invoke(player);
                    return bridge.profile(id).map(NicknameProfile::nickname).orElseGet(() -> {
                        try { return (String) player.getClass().getMethod("name").invoke(player); }
                        catch (ReflectiveOperationException ignored) { return ""; }
                    });
                } catch (ReflectiveOperationException e) { return ""; }
            };
            int refresh = Math.max(20, bridge.getConfig().getInt("custom-nameplates.refresh-ticks", 20));
            managerType.getMethod("registerPlayerPlaceholder", String.class, int.class, Function.class).invoke(manager, PLACEHOLDER, refresh, value);
            Bukkit.getOnlinePlayers().forEach(player -> refresh(player.getUniqueId()));
            bridge.getLogger().info("CustomNameplates placeholder를 등록했습니다: %" + PLACEHOLDER + "%");
        } catch (ReflectiveOperationException e) {
            bridge.getLogger().warning("CustomNameplates placeholder 등록 실패: " + e.getMessage());
        }
    }

    void refresh(UUID id) {
        try {
            Class<?> apiType = Class.forName("net.momirealms.customnameplates.api.CustomNameplatesAPI");
            Object api = apiType.getMethod("getInstance").invoke(null);
            Object player = apiType.getMethod("getPlayer", UUID.class).invoke(api, id);
            if (player == null) return;
            Object plugin = apiType.getMethod("plugin").invoke(api);
            Object manager = plugin.getClass().getMethod("getPlaceholderManager").invoke(plugin);
            Object placeholder = manager.getClass().getMethod("getRegisteredPlaceholder", String.class).invoke(manager, PLACEHOLDER);
            if (placeholder != null) {
                Method force = Arrays.stream(player.getClass().getMethods()).filter(m -> m.getName().equals("forceUpdatePlaceholders")).findFirst().orElseThrow();
                force.invoke(player, Set.of(placeholder), List.of(player));
            }
        } catch (ReflectiveOperationException | NoSuchElementException ignored) { }
    }

    void close() {
        if (subscription == null) return;
        try { subscription.getClass().getMethod("dispose").invoke(subscription); }
        catch (ReflectiveOperationException ignored) { }
    }
}
