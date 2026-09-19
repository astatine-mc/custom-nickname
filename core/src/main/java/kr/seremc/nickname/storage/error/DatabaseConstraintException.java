package kr.seremc.nickname.storage.error;

/** 닉네임 unique 제약 등 데이터 무결성 제약을 위반했을 때 발생합니다. */
public final class DatabaseConstraintException extends DatabaseException {
    public DatabaseConstraintException(String operation, String sqlState, int vendorCode, Throwable cause) {
        super("이미 사용 중인 닉네임이거나 중복된 요청입니다.", operation, sqlState, vendorCode, false, cause);
    }
}
