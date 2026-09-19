package kr.seremc.nickname.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NamePolicyTest {
  private final NamePolicy policy = new NamePolicy(2, 16, List.of("관리자"));

  @Test
  void acceptsKoreanAndNormalizesUnicode() {
    assertEquals("별빛_12", policy.validate("별빛_12"));
    assertEquals("é", NamePolicy.key("E\u0301"));
  }

  @Test
  void rejectsBlockedInvalidAndOutOfRangeNames() {
    assertThrows(IllegalArgumentException.class, () -> policy.validate("관리자"));
    assertThrows(IllegalArgumentException.class, () -> policy.validate("한"));
    assertThrows(IllegalArgumentException.class, () -> policy.validate("이름!"));
  }
}
