package kr.seremc.nickname.storage.sql;

import java.sql.SQLException;
import kr.seremc.nickname.storage.error.*;

/** JDBC 예외를 저장소 구현에 종속되지 않는 도메인 예외로 변환합니다. SQLState를 우선 사용하고 MariaDB 고유 코드를 보조로 사용합니다. */
public final class SqlErrorTranslator {
  private SqlErrorTranslator() {}

  /**
   * SQLState와 vendor code를 정해진 우선순위로 분류합니다. cause를 보존하지만 사용자 메시지에 원본 SQL 문자열을 삽입하지 않습니다. retryable은
   * 분류 힌트이며 commit 결과가 불확실한 쓰기를 자동 재실행하라는 의미가 아닙니다.
   */
  public static DatabaseException translate(String operation, SQLException error) {
    String state = error.getSQLState() == null ? "" : error.getSQLState();
    int code = error.getErrorCode();
    if ("28000".equals(state) || code == 1044 || code == 1045)
      return new DatabaseAuthenticationException(operation, state, code, error);
    if (state.startsWith("08"))
      return new DatabaseUnavailableException(operation, state, code, error);
    if ("40001".equals(state) || code == 1205 || code == 1213)
      return new DatabaseTransactionException(operation, state, code, error);
    if (state.startsWith("23") || code == 1062)
      return new DatabaseConstraintException(operation, state, code, error);
    if (state.startsWith("42") || "3D000".equals(state) || code == 1146)
      return new DatabaseSchemaException(operation, state, code, error);
    return new DatabaseException(
        "닉네임 데이터베이스 작업에 실패했습니다. 관리자에게 로그를 전달하세요.", operation, state, code, false, error);
  }
}
