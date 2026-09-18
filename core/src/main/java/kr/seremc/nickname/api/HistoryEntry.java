package kr.seremc.nickname.api;
import java.time.Instant;
public record HistoryEntry(long id, String oldName, String newName, String actorType, String actorId, String reason, Instant createdAt) {}
