package kr.seremc.nickname.storage.error;

/** 잠금 대기 또는 데드락으로 트랜잭션을 다시 실행해야 할 때 발생합니다. */
public final class DatabaseTransactionException extends DatabaseException {
    public DatabaseTransactionException(String operation, String sqlState, int vendorCode, Throwable cause) {
        super("다른 닉네임 요청과 충돌했습니다. 잠시 후 다시 시도하세요.", operation, sqlState, vendorCode, true, cause);
    }
}
