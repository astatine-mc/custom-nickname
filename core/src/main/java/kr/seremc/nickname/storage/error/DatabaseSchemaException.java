package kr.seremc.nickname.storage.error;

/** 테이블 생성, 컬럼, 권한 등 스키마 준비 과정이 실패했을 때 발생합니다. */
public final class DatabaseSchemaException extends DatabaseException {
    public DatabaseSchemaException(String operation, String sqlState, int vendorCode, Throwable cause) {
        super("닉네임 데이터베이스 스키마를 준비하지 못했습니다. DB 권한과 MariaDB 버전을 확인하세요.", operation, sqlState, vendorCode, false, cause);
    }
}
