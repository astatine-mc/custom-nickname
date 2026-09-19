package kr.seremc.nickname.api;

import java.util.UUID;

/**
 * 발급 결과입니다. databaseId는 운영 조회용 DB 행 ID이고 token은 실제 아이템에 전달할 UUID입니다. token 원문은 DB에 저장하지 않으므로 이 결과를
 * 로그에 출력하지 않습니다.
 */
public record TicketIssue(long databaseId, UUID token) {}
