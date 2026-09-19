package kr.seremc.nickname.velocity;

import kr.seremc.nickname.api.NicknameProfile;
import kr.seremc.nickname.api.NicknameService;
import me.neznamy.tab.api.*;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.placeholder.PlayerPlaceholder;
import org.slf4j.Logger;

/**
 * TAB Developer API에 캐시 기반 placeholder를 제공하는 Velocity 어댑터입니다. polling 간격은 최소 1초입니다.
 * reload 중 원문 노출은 TAB 렌더링 상태에도 의존하므로 이 간격만으로 방지하지는 못합니다.
 */
final class TabIntegration {
  private final NicknameService names;
  private final Logger logger;
  private final int refreshMillis;
  private PlayerPlaceholder nickname, account, custom;

  /** 캐시 서비스와 로거를 받고 polling 간격을 최소 1000ms로 제한합니다. */
  TabIntegration(NicknameService names, Logger logger, int refreshMillis) {
    this.names = names;
    this.logger = logger;
    this.refreshMillis = Math.max(1000, refreshMillis);
  }

  /** TAB load와 player load 이벤트를 구독하고 현재 온라인 값도 등록합니다. 연동 실패는 DB 서비스를 중지하지 않습니다. */
  void initialize() {
    try {
      TabAPI api = TabAPI.getInstance();
      api.getEventBus().register(TabLoadEvent.class, event -> register());
      api.getEventBus().register(PlayerLoadEvent.class, event -> refresh(event.getPlayer()));
      register();
    } catch (Throwable e) {
      logger.warn("TAB 연동을 시작하지 못했습니다. 닉네임 저장 기능은 계속 동작합니다.", e);
    }
  }

  /** TAB reload 완료 시 기존 등록을 교체하고 온라인 플레이어 값을 한 번 즉시 채웁니다. */
  private void register() {
    try {
      var manager = TabAPI.getInstance().getPlaceholderManager();
      unregister(manager, "%customnickname_nickname%");
      unregister(manager, "%customnickname_account%");
      unregister(manager, "%customnickname_is_custom%");
      nickname =
          manager.registerPlayerPlaceholder(
              "%customnickname_nickname%",
              refreshMillis,
              p ->
                  names.cached(p.getUniqueId()).map(NicknameProfile::nickname).orElse(p.getName()));
      account =
          manager.registerPlayerPlaceholder(
              "%customnickname_account%",
              refreshMillis,
              p ->
                  names
                      .cached(p.getUniqueId())
                      .map(NicknameProfile::accountName)
                      .orElse(p.getName()));
      custom =
          manager.registerPlayerPlaceholder(
              "%customnickname_is_custom%",
              refreshMillis,
              p ->
                  Boolean.toString(
                      names.cached(p.getUniqueId()).map(NicknameProfile::custom).orElse(false)));
      for (TabPlayer player : TabAPI.getInstance().getOnlinePlayers()) refresh(player);
    } catch (Throwable e) {
      logger.warn("TAB placeholder 재등록 실패", e);
    }
  }

  /** 같은 ID를 다시 등록하기 전에 해제를 시도합니다. TAB 버전별 해제 실패는 현재 구현에서 무시합니다. */
  private void unregister(me.neznamy.tab.api.placeholder.PlaceholderManager manager, String id) {
    try {
      manager.unregisterPlaceholder(id);
    } catch (Throwable ignored) {
    }
  }

  /** DB commit 후 TAB을 먼저 갱신하는 표시 우선순위의 첫 단계입니다. */
  void refresh(NicknameProfile profile) {
    try {
      TabPlayer player = TabAPI.getInstance().getPlayer(profile.playerId());
      if (player == null || !player.isLoaded()) return;
      nickname.updateValue(player, profile.nickname());
      account.updateValue(player, profile.accountName());
      custom.updateValue(player, Boolean.toString(profile.custom()));
    } catch (Throwable e) {
      logger.debug("TAB 즉시 갱신 실패: {}", profile.playerId(), e);
    }
  }

  /** 플레이어의 캐시 프로필이 있는 경우에만 즉시 갱신합니다. 캐시 미스는 등록된 계정명 fallback을 사용합니다. */
  private void refresh(TabPlayer player) {
    names.cached(player.getUniqueId()).ifPresent(this::refresh);
  }
}
