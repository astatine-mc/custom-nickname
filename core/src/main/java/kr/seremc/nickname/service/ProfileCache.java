package kr.seremc.nickname.service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.seremc.nickname.api.NicknameProfile;

/** 비동기 응답 순서가 뒤집혀도 최신 revision만 표시 계층에 전달하는 캐시입니다. */
public final class ProfileCache {
  private final ConcurrentHashMap<UUID, NicknameProfile> values = new ConcurrentHashMap<>();

  public NicknameProfile accept(NicknameProfile incoming) {
    return values.compute(incoming.playerId(), (id, old) ->
        old == null || incoming.revision() > old.revision() ? incoming : old);
  }

  public Optional<NicknameProfile> find(UUID id) { return Optional.ofNullable(values.get(id)); }
  public void clear() { values.clear(); }
}
