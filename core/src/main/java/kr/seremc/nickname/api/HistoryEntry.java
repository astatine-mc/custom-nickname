package kr.seremc.nickname.api;

import java.time.Instant;

/**
 * 관리자 기록 화면에 전달하는 이력 한 행입니다. 최초 생성의 oldName은 null일 수 있습니다. actorType과 actorId는 변경 주체, reason은 사유이며
 * createdAt은 DB에서 읽은 시각입니다. DB 계정명 이력 컬럼은 이 DTO에 포함되지 않으므로 계정명 변경 상세 조회는 별도 확장이 필요합니다.
 */
public record HistoryEntry(
    long id,
    String oldName,
    String newName,
    String actorType,
    String actorId,
    String reason,
    Instant createdAt) {}
