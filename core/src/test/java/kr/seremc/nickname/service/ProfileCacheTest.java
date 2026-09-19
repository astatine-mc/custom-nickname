package kr.seremc.nickname.service;
import java.util.UUID;
import kr.seremc.nickname.api.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProfileCacheTest {
  @Test void staleReplyCannotReplaceLatestProfile() {
    UUID id=UUID.randomUUID(); ProfileCache cache=new ProfileCache();
    NicknameProfile latest=new NicknameProfile(id,"Steve","최신",true,3);
    cache.accept(latest);
    assertSame(latest,cache.accept(new NicknameProfile(id,"Steve","과거",true,2)));
    assertSame(latest,cache.find(id).orElseThrow());
  }
  @Test void validTicketForAnotherOwnerMustNotBeDeleted() {
    assertFalse(new TicketRejectedException(TicketStatus.ISSUED,"다른 소유자").removeItem());
    assertTrue(new TicketRejectedException(TicketStatus.USED,"사용됨").removeItem());
  }
}
