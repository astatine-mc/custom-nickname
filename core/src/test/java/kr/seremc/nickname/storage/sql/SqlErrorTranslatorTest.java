package kr.seremc.nickname.storage.sql;

import kr.seremc.nickname.storage.error.*;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SqlErrorTranslatorTest {
    @Test void classifiesMariaDbAuthenticationError() {
        assertInstanceOf(DatabaseAuthenticationException.class, SqlErrorTranslator.translate("연결", new SQLException("denied", "28000", 1045)));
    }

    @Test void classifiesRetryableConnectionAndTransactionErrors() {
        assertInstanceOf(DatabaseUnavailableException.class, SqlErrorTranslator.translate("조회", new SQLException("offline", "08001", 0)));
        assertInstanceOf(DatabaseTransactionException.class, SqlErrorTranslator.translate("변경", new SQLException("deadlock", "40001", 1213)));
    }

    @Test void classifiesConstraintAndSchemaErrors() {
        assertInstanceOf(DatabaseConstraintException.class, SqlErrorTranslator.translate("변경", new SQLException("duplicate", "23000", 1062)));
        assertInstanceOf(DatabaseSchemaException.class, SqlErrorTranslator.translate("초기화", new SQLException("missing", "42S02", 1146)));
    }
}
