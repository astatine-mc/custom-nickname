package kr.seremc.nickname.api;

import java.util.UUID;

/**
 * DB와 메시지 양쪽에서 사용하는 불변 프로필 스냅샷입니다.
 *
 * @param playerId 계정명 변경과 무관한 영구 식별자
 * @param accountName 마지막 접속에서 확인한 실제 계정명
 * @param nickname 화면 표시 및 역조회에 사용하는 이름
 * @param custom 직접 지정한 닉네임이면 true; false이면 접속 계정명에 따라 갱신
 * @param revision 저장된 변경 순서. 낮은 버전의 수신 결과는 표시 캐시에 적용하지 않습니다.
 */
public record NicknameProfile(
    UUID playerId, String accountName, String nickname, boolean custom, long revision) {}
