package kr.seremc.nickname.protocol;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 프록시가 요청한 변경만 현재 backend에서 한 번 회신할 수 있도록 제한합니다. */
public final class PendingChanges {
  private record Entry(UUID request, String nickname, Object connection, long created) {}
  private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
  private final LongSupplier clock;
  private static final long TTL = Duration.ofSeconds(15).toNanos();

  public PendingChanges() { this(System::nanoTime); }
  public PendingChanges(LongSupplier clock) { this.clock = clock; }
  public boolean issue(UUID player, UUID request, String nickname, Object connection) {
    Entry next = new Entry(request, nickname, connection, clock.getAsLong());
    return entries.compute(player, (id, old) -> old == null || clock.getAsLong() - old.created >= TTL ? next : old) == next;
  }
  public boolean consume(UUID player, UUID request, String nickname, Object connection) {
    Entry entry = entries.get(player);
    if (entry == null) return false;
    if (clock.getAsLong() - entry.created >= TTL) { entries.remove(player, entry); return false; }
    return entry.request.equals(request) && entry.nickname.equals(nickname)
        && entry.connection == connection && entries.remove(player, entry);
  }
  public void clear(UUID player) { entries.remove(player); }
}
