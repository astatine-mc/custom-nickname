package kr.seremc.nickname.api;
public final class TicketRejectedException extends IllegalArgumentException {
  private final TicketStatus status;
  public TicketRejectedException(TicketStatus status, String message) { super(message); this.status=status; }
  public TicketStatus status() { return status; }
  public boolean removeItem() { return status != TicketStatus.ISSUED; }
}
