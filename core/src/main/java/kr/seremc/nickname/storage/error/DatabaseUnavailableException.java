package kr.seremc.nickname.storage.error;

/** 데이터베이스 서버에 연결할 수 없거나 연결이 끊겼을 때 발생합니다. */
public final class DatabaseUnavailableException extends DatabaseException {
    public DatabaseUnavailableException(String operation, String sqlState, int vendorCode, Throwable cause) {
        super("닉네임 데이터베이스에 연결할 수 없습니다. 잠시 후 다시 시도하세요.", operation, sqlState, vendorCode, true, cause);
    }
}
