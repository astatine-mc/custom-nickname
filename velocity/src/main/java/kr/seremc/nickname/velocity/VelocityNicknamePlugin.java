package kr.seremc.nickname.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.connection.*;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.zaxxer.hikari.*;
import kr.seremc.nickname.api.*;
import kr.seremc.nickname.protocol.NicknameProtocol;
import kr.seremc.nickname.protocol.NicknameProtocol.Packet;
import kr.seremc.nickname.service.*;
import kr.seremc.nickname.storage.MariaRepository;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Plugin(id="customnickname",name="CustomNickname",version="2.0.0",authors={"Seremc"},dependencies={@Dependency(id="tab",optional=true)})
public final class VelocityNicknamePlugin {
 static final MinecraftChannelIdentifier CHANNEL=MinecraftChannelIdentifier.from(NicknameProtocol.CHANNEL);
 private final ProxyServer proxy; private final Logger logger; private final Path dataDirectory;
 private volatile DefaultNicknameService names; private volatile TabIntegration tab; private volatile boolean stopping;

 @Inject public VelocityNicknamePlugin(ProxyServer proxy,Logger logger,@DataDirectory Path dataDirectory){this.proxy=proxy;this.logger=logger;this.dataDirectory=dataDirectory;}

 @Subscribe public void onInitialize(ProxyInitializeEvent event){
  try{
   VelocityConfig config=VelocityConfig.load(dataDirectory);
   HikariConfig hikari=new HikariConfig();hikari.setJdbcUrl(config.get("database.url"));hikari.setUsername(config.get("database.username"));hikari.setPassword(config.secret("database.password-env","database.password"));hikari.setDriverClassName("org.mariadb.jdbc.Driver");hikari.setMaximumPoolSize(Math.max(2,config.integer("database.pool-size",6)));hikari.setConnectionTimeout(3000);hikari.setPoolName("CustomNicknameVelocity");
   MariaRepository repository=new MariaRepository(new HikariDataSource(hikari));repository.initialize();
   names=new DefaultNicknameService(repository,new NamePolicy(config.integer("nickname.min-length",2),config.integer("nickname.max-length",16),config.list("nickname.blocked")),this::profileChanged);
   proxy.getChannelRegistrar().register(CHANNEL);
   new VelocityCommands(this,proxy,names,logger).register();
   if(proxy.getPluginManager().isLoaded("tab")){tab=new TabIntegration(names,logger,config.integer("tab.refresh-milliseconds",1000));tab.initialize();}
   logger.info("CustomNickname Velocity가 시작되었습니다.");
  }catch(Exception e){logger.error("CustomNickname Velocity 시작 실패",e);throw new IllegalStateException(e);}
 }

 @Subscribe public EventTask onLogin(LoginEvent event){
  if(names==null)return null;
  CompletableFuture<?> future=names.synchronizeAccount(event.getPlayer().getUniqueId(),event.getPlayer().getUsername()).exceptionally(error->{logger.warn("닉네임 접속 동기화 실패: {}",event.getPlayer().getUsername(),unwrap(error));event.setResult(com.velocitypowered.api.event.ResultedEvent.ComponentResult.denied(Component.text("닉네임 정보를 불러오지 못했습니다. 잠시 후 다시 접속해 주세요.")));return null;});
  return EventTask.resumeWhenComplete(future);
 }

 @Subscribe public void onServerConnected(ServerPostConnectEvent event){
  UUID id=event.getPlayer().getUniqueId();
  names.findById(id).thenAccept(found->found.ifPresent(profile->sendProfile(event.getPlayer(),profile))).exceptionally(this::logFailure);
 }

 @Subscribe public void onPluginMessage(PluginMessageEvent event){
  if(!CHANNEL.equals(event.getIdentifier()))return;
  event.setResult(PluginMessageEvent.ForwardResult.handled());
  if(!(event.getSource() instanceof ServerConnection backend))return;
  Packet packet;
  try{packet=NicknameProtocol.decode(event.getData());}catch(IllegalArgumentException e){logger.warn("손상된 닉네임 메시지: {}",e.getMessage());return;}
  Player player=backend.getPlayer();
  if(!packet.playerId().equals(player.getUniqueId())){logger.warn("닉네임 메시지 UUID 위조 차단: {}",player.getUsername());return;}
  if(player.getCurrentServer().isEmpty()||player.getCurrentServer().get()!=backend){logger.warn("이전 서버의 닉네임 메시지 차단: {}",player.getUsername());return;}
  switch(packet.type()){
   case PROFILE_REQUEST,BRIDGE_READY -> names.findById(player.getUniqueId()).thenAccept(found->found.ifPresent(p->sendProfile(player,p))).exceptionally(this::logFailure);
   case TICKET_USE -> handleTicketUse(player,packet);
   case TICKET_INVENTORY_STATE -> handleInventoryState(player,packet);
   default -> logger.warn("백엔드가 보낼 수 없는 메시지 차단: {}",packet.type());
  }
 }

 private void handleTicketUse(Player player,Packet packet){
  try{
   UUID token=UUID.fromString(packet.field(0));String nickname=packet.field(1);
   names.changeWithTicket(player.getUniqueId(),nickname,token,packet.requestId()).whenComplete((profile,error)->{
    if(error==null){send(player,NicknameProtocol.Type.REMOVE_TICKET_ID,packet.requestId(),token.toString());send(player,NicknameProtocol.Type.NICKNAME_RESULT,packet.requestId(),"SUCCESS",profile.nickname());sendProfile(player,profile);return;}
    Throwable cause=unwrap(error);if(cause instanceof TicketRejectedException rejected&&rejected.removeItem())send(player,NicknameProtocol.Type.REMOVE_TICKET_ID,packet.requestId(),token.toString());send(player,NicknameProtocol.Type.NICKNAME_RESULT,packet.requestId(),"ERROR",safeMessage(cause));
   });
  }catch(IllegalArgumentException e){send(player,NicknameProtocol.Type.NICKNAME_RESULT,packet.requestId(),"ERROR",e.getMessage());}
 }

 private void handleInventoryState(Player player,Packet packet){
  List<UUID> tokens=new ArrayList<>();for(String field:packet.fields())try{tokens.add(UUID.fromString(field));}catch(IllegalArgumentException ignored){}
  names.ticketStatuses(tokens).thenAccept(states->{List<String> invalid=states.entrySet().stream().filter(e->!e.getValue().usable()).map(e->e.getKey().toString()).toList();for(int from=0;from<invalid.size();from+=30){List<String> chunk=invalid.subList(from,Math.min(from+30,invalid.size()));send(player,NicknameProtocol.Type.TICKET_RECONCILE_RESULT,packet.requestId(),chunk.toArray(String[]::new));}}).exceptionally(this::logFailure);
 }

 void requestTicketUse(Player player,String nickname){send(player,NicknameProtocol.Type.TICKET_USE_REQUEST,UUID.randomUUID(),nickname);}
 void deliverTicket(Player player,TicketIssue issue){send(player,NicknameProtocol.Type.TICKET_ITEM_CREATE,UUID.randomUUID(),issue.token().toString());}
 private void profileChanged(NicknameProfile profile){if(stopping)return;proxy.getScheduler().buildTask(this,()->{if(tab!=null)tab.refresh(profile);proxy.getPlayer(profile.playerId()).ifPresent(player->sendProfile(player,profile));}).schedule();}
 private void sendProfile(Player player,NicknameProfile p){if(tab!=null)tab.refresh(p);send(player,NicknameProtocol.Type.PROFILE_SYNC,UUID.randomUUID(),p.accountName(),p.nickname(),Boolean.toString(p.custom()),Long.toString(p.revision()));}
 private void send(Player player,NicknameProtocol.Type type,UUID request,String...fields){player.getCurrentServer().ifPresent(connection->connection.sendPluginMessage(CHANNEL,NicknameProtocol.encode(type,request,player.getUniqueId(),fields)));}
 private Void logFailure(Throwable error){logger.warn("닉네임 비동기 처리 실패",unwrap(error));return null;}
 static Throwable unwrap(Throwable error){Throwable e=error;while((e instanceof CompletionException||e instanceof ExecutionException)&&e.getCause()!=null)e=e.getCause();return e;}
 static String safeMessage(Throwable error){String message=error.getMessage();return message==null||message.isBlank()?"닉네임 요청을 처리하지 못했습니다.":message;}
 @Subscribe public void onShutdown(ProxyShutdownEvent event){stopping=true;if(names!=null)names.close();}
}
