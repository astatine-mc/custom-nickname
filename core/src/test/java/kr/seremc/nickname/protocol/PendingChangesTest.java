package kr.seremc.nickname.protocol;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PendingChangesTest {
  @Test void rejectsUnsolicitedAlteredAndReplayedReplies() {
    PendingChanges gate = new PendingChanges(); UUID p=UUID.randomUUID(), r=UUID.randomUUID(); Object c=new Object();
    assertFalse(gate.consume(p,r,"별빛",c));
    assertTrue(gate.issue(p,r,"별빛",c));
    assertFalse(gate.issue(p,UUID.randomUUID(),"다른이름",c));
    assertFalse(gate.consume(p,r,"변조",c));
    assertFalse(gate.consume(p,r,"별빛",new Object()));
    assertTrue(gate.consume(p,r,"별빛",c));
    assertFalse(gate.consume(p,r,"별빛",c));
  }
  @Test void expiresAndClearsRequests() {
    AtomicLong clock=new AtomicLong(); PendingChanges gate=new PendingChanges(clock::get);
    UUID p=UUID.randomUUID(),r=UUID.randomUUID(); Object c=new Object();
    gate.issue(p,r,"별빛",c); clock.set(15_000_000_000L);
    assertFalse(gate.consume(p,r,"별빛",c));
    assertTrue(gate.issue(p,r,"별빛",c)); gate.clear(p);
    assertFalse(gate.consume(p,r,"별빛",c));
  }
}
