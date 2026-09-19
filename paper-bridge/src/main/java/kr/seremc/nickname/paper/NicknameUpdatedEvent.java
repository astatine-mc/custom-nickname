package kr.seremc.nickname.paper;

import kr.seremc.nickname.api.NicknameProfile;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Paper 표시 계층이 새 revision을 적용한 뒤 발생하는 로컬 이벤트입니다.
 * 채팅, HUD, scoreboard 플러그인은 이 이벤트에서 UUID와 표시 닉네임을 읽어 갱신합니다.
 */
public final class NicknameUpdatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NicknameProfile profile;

    public NicknameUpdatedEvent(NicknameProfile profile) { this.profile = profile; }
    public NicknameProfile profile() { return profile; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
