package kr.seremc.nickname.storage.error;

/** 데이터베이스 사용자명·비밀번호 또는 권한이 올바르지 않을 때 발생합니다. */
public final class DatabaseAuthenticationException extends DatabaseException {
  public DatabaseAuthenticationException(
      String operation, String sqlState, int vendorCode, Throwable cause) {
    super(
        "닉네임 데이터베이스 인증에 실패했습니다. database.password 또는 NICKNAME_DB_PASSWORD를 확인하세요.",
        operation,
        sqlState,
        vendorCode,
        false,
        cause);
  }
}
