package kr.seremc.nickname.paper;

import kr.seremc.nickname.api.NicknameProfile;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Paper 표시 계층이 새 revision을 적용한 뒤 발생하는 로컬 이벤트입니다. 채팅, HUD, scoreboard 플러그인은 이 이벤트에서 UUID와 표시 닉네임을 읽어
 * 갱신합니다.
 */
public final class NicknameUpdatedEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final NicknameProfile profile;

  /** 적용된 불변 프로필을 보존합니다. 기본 Bukkit 동기 이벤트이므로 메인 스레드에서 발생시켜야 합니다. */
  public NicknameUpdatedEvent(NicknameProfile profile) {
    this.profile = profile;
  }

  /** 구독자는 이 UUID와 revision을 기준으로 자체 표시를 갱신할 수 있습니다. */
  public NicknameProfile profile() {
    return profile;
  }

  /** Bukkit이 이 이벤트의 구독자를 관리하는 공통 핸들러 목록입니다. */
  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  /** Bukkit 이벤트 등록 규약에서 요구하는 정적 핸들러 접근자입니다. */
  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
