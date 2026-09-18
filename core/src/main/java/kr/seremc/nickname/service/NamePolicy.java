package kr.seremc.nickname.service;
import java.text.Normalizer;
import java.util.*;
public final class NamePolicy {
 private final int min,max; private final Set<String> blocked=new HashSet<>();
 public NamePolicy(int min,int max,Collection<String> blocked) { if(min<1||max<min||max>32) throw new IllegalArgumentException("닉네임 길이 설정 오류"); this.min=min;this.max=max;blocked.forEach(n->this.blocked.add(key(n))); }
 public static String key(String name) { return Normalizer.normalize(name,Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
 public String validate(String input) { String name=Normalizer.normalize(input,Normalizer.Form.NFC); if(name.length()<min||name.length()>max||!name.matches("[가-힣a-zA-Z0-9_]+")) throw new IllegalArgumentException("닉네임은 한글 완성형/영문/숫자/_ "+min+"~"+max+"자로 입력하세요."); if(blocked.contains(key(name))) throw new IllegalArgumentException("사용할 수 없는 닉네임입니다."); return name; }
}
