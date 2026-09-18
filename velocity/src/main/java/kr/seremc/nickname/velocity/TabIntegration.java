package kr.seremc.nickname.velocity;

import kr.seremc.nickname.api.NicknameProfile;
import kr.seremc.nickname.api.NicknameService;
import me.neznamy.tab.api.*;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.placeholder.PlayerPlaceholder;
import org.slf4j.Logger;

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
 void refresh(NicknameProfile profile){
  try{TabPlayer player=TabAPI.getInstance().getPlayer(profile.playerId());if(player==null||!player.isLoaded())return;nickname.updateValue(player,profile.nickname());account.updateValue(player,profile.accountName());custom.updateValue(player,Boolean.toString(profile.custom()));}catch(Throwable e){logger.debug("TAB 즉시 갱신 실패: {}",profile.playerId(),e);}
 }
 private void refresh(TabPlayer player){names.cached(player.getUniqueId()).ifPresent(this::refresh);}
}
