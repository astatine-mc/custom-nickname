package kr.seremc.nickname.service;

import kr.seremc.nickname.api.*;
import kr.seremc.nickname.storage.MariaRepository;
import kr.seremc.nickname.storage.sql.SqlErrorTranslator;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.sql.SQLException;

/**
 * 비동기 API, 입력 정책, 프로필 캐시를 조합하는 서비스 계층입니다.
 * 저장소 작업은 고정 크기 executor에서 실행하므로 Velocity 또는 Paper 메인 스레드를 막지 않습니다.
 */
public final class DefaultNicknameService implements NicknameService,AutoCloseable {
 private final MariaRepository repository; private final NamePolicy policy; private final Consumer<NicknameProfile> changed;
 private final Map<UUID,NicknameProfile> cache=new ConcurrentHashMap<>();
 private final ExecutorService executor=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),Thread.ofPlatform().daemon().name("nickname-db-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
 @FunctionalInterface private interface Task<T>{T run()throws Exception;}
 public DefaultNicknameService(MariaRepository repository,NamePolicy policy,Consumer<NicknameProfile> changed){this.repository=repository;this.policy=policy;this.changed=changed;}
 /** SQL 예외를 요청 종류별로 번역한 뒤 CompletableFuture 실패 결과로 반환합니다. */
 private <T>CompletableFuture<T> async(Task<T> task){try{return CompletableFuture.supplyAsync(()->{try{return task.run();}catch(SQLException error){throw new CompletionException(SqlErrorTranslator.translate("닉네임 저장소 작업",error));}catch(Exception error){throw new CompletionException(error);}},executor);}catch(RejectedExecutionException e){return CompletableFuture.failedFuture(new IllegalStateException("요청이 많습니다. 잠시 후 다시 시도하세요."));}}
 /** 더 낮은 revision의 비동기 결과가 최신 캐시를 덮어쓰지 않도록 보장합니다. */
 private NicknameProfile committed(NicknameProfile profile){cache.compute(profile.playerId(),(id,old)->old==null||old.revision()<=profile.revision()?profile:old);changed.accept(profile);return profile;}
 @Override public CompletableFuture<Optional<NicknameProfile>> findById(UUID id){NicknameProfile hit=cache.get(id);if(hit!=null)return CompletableFuture.completedFuture(Optional.of(hit));return async(()->{Optional<NicknameProfile> found=repository.find("player_uuid",id.toString());found.ifPresent(this::committed);return found;});}
 @Override public CompletableFuture<Optional<NicknameProfile>> findByNickname(String name){return async(()->repository.find("nickname_key",NamePolicy.key(name)));}
 @Override public CompletableFuture<Optional<NicknameProfile>> findByAccountName(String name){return async(()->repository.find("account_name_key",NamePolicy.key(name)));}
 @Override public CompletableFuture<NicknameProfile> synchronizeAccount(UUID id,String name){return async(()->committed(repository.synchronize(id,name)));}
 @Override public CompletableFuture<NicknameProfile> changeWithTicket(UUID id,String name,UUID token,UUID request){return async(()->committed(repository.changeWithTicket(id,policy.validate(name),token,request)));}
 @Override public CompletableFuture<NicknameProfile> forceChange(UUID id,String name,String actorType,String actorId,String reason,UUID request){return async(()->committed(repository.forceChange(id,policy.validate(name),actorType,actorId,reason,request)));}
 @Override public CompletableFuture<List<HistoryEntry>> history(UUID id,int page){return async(()->repository.history(id,page));}
 @Override public CompletableFuture<TicketIssue> issueTicket(String type,String actor,UUID target){return async(()->repository.issueTicket(type,actor,target));}
 @Override public CompletableFuture<Map<UUID,TicketStatus>> ticketStatuses(List<UUID> tokens){return async(()->repository.ticketStatuses(tokens));}
 @Override public Optional<NicknameProfile> cached(UUID id){return Optional.ofNullable(cache.get(id));}
 @Override public void close(){executor.shutdown();try{if(!executor.awaitTermination(10,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException e){executor.shutdownNow();Thread.currentThread().interrupt();}repository.close();cache.clear();}
}
