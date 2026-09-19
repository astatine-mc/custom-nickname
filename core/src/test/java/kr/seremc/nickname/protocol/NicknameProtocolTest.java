package kr.seremc.nickname.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class NicknameProtocolTest {
  @Test
  void roundTripsUtf8Packet() {
    UUID request = UUID.randomUUID(), player = UUID.randomUUID();
    byte[] encoded =
        NicknameProtocol.encode(
            NicknameProtocol.Type.PROFILE_SYNC, request, player, "Steve", "별빛", "true", "7");
    var decoded = NicknameProtocol.decode(encoded);
    assertEquals(NicknameProtocol.Type.PROFILE_SYNC, decoded.type());
    assertEquals(request, decoded.requestId());
    assertEquals(player, decoded.playerId());
    assertEquals("별빛", decoded.field(1));
  }

  @Test
  void rejectsTrailingAndMalformedData() {
    byte[] valid =
        NicknameProtocol.encode(
            NicknameProtocol.Type.PROFILE_REQUEST, UUID.randomUUID(), UUID.randomUUID());
    byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
    assertThrows(IllegalArgumentException.class, () -> NicknameProtocol.decode(trailing));
    assertThrows(
        IllegalArgumentException.class, () -> NicknameProtocol.decode(new byte[] {1, 2, 3}));
  }
}
