package kr.seremc.nickname.api;

/** 변경권의 상태 때문에 사용이 거절된 경우입니다. SQL 장애와 구분하여 Paper에 제거 여부를 전달합니다. */
public final class TicketRejectedException extends IllegalArgumentException {
  private final TicketStatus status;

  /** 판정 상태와 사용자 안내문을 보존합니다. ISSUED 상태 거절은 아이템 제거 대상이 아닙니다. */
  public TicketRejectedException(TicketStatus status, String message) {
    super(message);
    this.status = status;
  }

  /** 거절 당시 판정 상태입니다. DB 상태를 변경하는 메서드가 아닙니다. */
  public TicketStatus status() {
    return status;
  }

  /** ISSUED 이외의 상태일 때만 현재 인벤토리의 동일 ID 아이템을 제거하도록 지시합니다. */
  public boolean removeItem() {
    return status != TicketStatus.ISSUED;
  }
}
