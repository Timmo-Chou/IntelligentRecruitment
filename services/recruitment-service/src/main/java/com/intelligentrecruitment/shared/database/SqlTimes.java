package com.intelligentrecruitment.shared.database;

import java.sql.Timestamp;
import java.time.Instant;

public final class SqlTimes {
    private SqlTimes() {}

    public static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
