package kr.seremc.nickname.api;

/**
 * DB 상태와 조회 실패가 아닌 미등록 상태 UNKNOWN을 구분합니다. DB 연결 오류는 UNKNOWN으로 대체하지 않고 예외로 전달해 정상 아이템의 오삭제를 막습니다.
 */
public enum TicketStatus {
  ISSUED,
  USED,
  EXPIRED,
  CANCELLED,
  UNKNOWN;

  public boolean usable() {
    return this == ISSUED;
  }
}
