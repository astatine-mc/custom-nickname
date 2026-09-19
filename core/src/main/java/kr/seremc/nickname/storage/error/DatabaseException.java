package kr.seremc.nickname.storage.error;

/**
 * MariaDB 작업이 실패했을 때 서비스 계층으로 전달하는 공통 예외입니다.
 *
 * <p>원본 {@code SQLException}은 로그의 원인으로 보존하지만, 플레이어에게는
 * 연결 문자열이나 SQL 문장을 노출하지 않는 안전한 한국어 메시지만 제공합니다.</p>
 */
public class DatabaseException extends RuntimeException {
    private final String operation;
    private final String sqlState;
    private final int vendorCode;
    private final boolean retryable;

    public DatabaseException(String message, String operation, String sqlState, int vendorCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.operation = operation;
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
        this.retryable = retryable;
    }

    /** 사람이 읽을 수 있는 실패 작업 이름입니다. */
    public String operation() { return operation; }
    /** JDBC SQLState입니다. 운영 로그와 모니터링에서 오류를 분류할 때 사용합니다. */
    public String sqlState() { return sqlState; }
    /** MariaDB 공급업체 오류 코드입니다. */
    public int vendorCode() { return vendorCode; }
    /** 짧은 지연 후 같은 읽기 또는 쓰기 요청을 다시 시도해도 되는지 나타냅니다. */
    public boolean retryable() { return retryable; }
}
