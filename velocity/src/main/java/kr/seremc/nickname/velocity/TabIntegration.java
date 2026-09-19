package kr.seremc.nickname.velocity;

import kr.seremc.nickname.api.NicknameProfile;
import kr.seremc.nickname.api.NicknameService;
import me.neznamy.tab.api.*;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.placeholder.PlayerPlaceholder;
import org.slf4j.Logger;

/**
 * TAB Developer API에 캐시 기반 placeholder를 제공하는 Velocity 어댑터입니다.
 * 1초보다 빠른 polling을 허용하지 않아 TAB reload 중 placeholder 원문이 반복 노출되는 일을 줄입니다.
 */
final class TabIntegration {
 private final NicknameService names; private final Logger logger; private final int refreshMillis;
 private PlayerPlaceholder nickname,account,custom;
 TabIntegration(NicknameService names,Logger logger,int refreshMillis){this.names=names;this.logger=logger;this.refreshMillis=Math.max(1000,refreshMillis);}
 void initialize(){
  try{
   TabAPI api=TabAPI.getInstance();
   api.getEventBus().register(TabLoadEvent.class,event->register());
   api.getEventBus().register(PlayerLoadEvent.class,event->refresh(event.getPlayer()));
   register();
  }catch(Throwable e){logger.warn("TAB 연동을 시작하지 못했습니다. 닉네임 저장 기능은 계속 동작합니다.",e);}
 }
 /** TAB reload 완료 시 기존 등록을 교체하고 온라인 플레이어 값을 한 번 즉시 채웁니다. */
 private void register(){
  try{
   var manager=TabAPI.getInstance().getPlaceholderManager();
   unregister(manager,"%customnickname_nickname%");unregister(manager,"%customnickname_account%");unregister(manager,"%customnickname_is_custom%");
   nickname=manager.registerPlayerPlaceholder("%customnickname_nickname%",refreshMillis,p->names.cached(p.getUniqueId()).map(NicknameProfile::nickname).orElse(p.getName()));
   account=manager.registerPlayerPlaceholder("%customnickname_account%",refreshMillis,p->names.cached(p.getUniqueId()).map(NicknameProfile::accountName).orElse(p.getName()));
   custom=manager.registerPlayerPlaceholder("%customnickname_is_custom%",refreshMillis,p->Boolean.toString(names.cached(p.getUniqueId()).map(NicknameProfile::custom).orElse(false)));
   for(TabPlayer player:TabAPI.getInstance().getOnlinePlayers())refresh(player);
  }catch(Throwable e){logger.warn("TAB placeholder 재등록 실패",e);}
 }
 private void unregister(me.neznamy.tab.api.placeholder.PlaceholderManager manager,String id){try{manager.unregisterPlaceholder(id);}catch(Throwable ignored){}}
 /** DB commit 후 TAB을 먼저 갱신하는 표시 우선순위의 첫 단계입니다. */
 void refresh(NicknameProfile profile){
  try{TabPlayer player=TabAPI.getInstance().getPlayer(profile.playerId());if(player==null||!player.isLoaded())return;nickname.updateValue(player,profile.nickname());account.updateValue(player,profile.accountName());custom.updateValue(player,Boolean.toString(profile.custom()));}catch(Throwable e){logger.debug("TAB 즉시 갱신 실패: {}",profile.playerId(),e);}
 }
 private void refresh(TabPlayer player){names.cached(player.getUniqueId()).ifPresent(this::refresh);}
}
