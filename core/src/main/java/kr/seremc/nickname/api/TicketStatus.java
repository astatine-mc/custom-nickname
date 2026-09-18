package kr.seremc.nickname.api;
public enum TicketStatus { ISSUED, USED, EXPIRED, CANCELLED, UNKNOWN; public boolean usable() { return this == ISSUED; } }
