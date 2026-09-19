package kr.seremc.nickname.service;

import java.text.Normalizer;
import java.util.*;

/** 사용자 입력을 NFC로 정규화한 뒤 길이·문자·금지어 규칙을 적용합니다. 저장소의 검색 키와 동일한 정규화 규칙을 사용해 조합형 한글과 대소문자 중복을 막습니다. */
public final class NamePolicy {
  private final int min, max;
  private final Set<String> blocked = new HashSet<>();

  public NamePolicy(int min, int max, Collection<String> blocked) {
    if (min < 1 || max < min || max > 32) throw new IllegalArgumentException("닉네임 길이 설정 오류");
    this.min = min;
    this.max = max;
    blocked.forEach(n -> this.blocked.add(key(n)));
  }

  /** DB unique 인덱스와 조회에 사용하는 대소문자 무시 정규화 키입니다. */
  public static String key(String name) {
    return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
  }

  /** 표시할 원문을 정규화해 검증하고, 실패하면 플레이어에게 보여줄 예외를 발생시킵니다. */
  public String validate(String input) {
    String name = Normalizer.normalize(input, Normalizer.Form.NFC);
    if (name.length() < min || name.length() > max || !name.matches("[가-힣a-zA-Z0-9_]+"))
      throw new IllegalArgumentException("닉네임은 한글 완성형/영문/숫자/_ " + min + "~" + max + "자로 입력하세요.");
    if (blocked.contains(key(name))) throw new IllegalArgumentException("사용할 수 없는 닉네임입니다.");
    return name;
  }
}
