package kr.seremc.nickname.api;
import java.util.*;
import java.util.concurrent.CompletableFuture;
public interface NicknameService {
 CompletableFuture<Optional<NicknameProfile>> findById(UUID id);
 CompletableFuture<Optional<NicknameProfile>> findByNickname(String nickname);
 CompletableFuture<Optional<NicknameProfile>> findByAccountName(String accountName);
 CompletableFuture<NicknameProfile> synchronizeAccount(UUID id, String accountName);
 CompletableFuture<NicknameProfile> changeWithTicket(UUID id, String nickname, UUID token, UUID requestId);
 CompletableFuture<NicknameProfile> forceChange(UUID id, String nickname, String actorType, String actorId, String reason, UUID requestId);
 CompletableFuture<List<HistoryEntry>> history(UUID id, int page);
 CompletableFuture<TicketIssue> issueTicket(String actorType, String actorId, UUID issuedTo);
 CompletableFuture<Map<UUID,TicketStatus>> ticketStatuses(List<UUID> tokens);
 Optional<NicknameProfile> cached(UUID id);
}
