package kr.seremc.nickname.paper;

import kr.seremc.nickname.api.NicknameProfile;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class NicknameUpdatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NicknameProfile profile;

    public NicknameUpdatedEvent(NicknameProfile profile) { this.profile = profile; }
    public NicknameProfile profile() { return profile; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
