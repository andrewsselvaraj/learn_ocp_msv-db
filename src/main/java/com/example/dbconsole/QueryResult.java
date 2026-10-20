package com.example.dbconsole;

import java.util.List;

/**
 * Outcome of running one SQL statement. Exactly one of these shapes is filled in:
 * a result set (columns + rows), an update count, or an error message.
 */
public record QueryResult(
        String sql,
        List<String> columns,
        List<List<String>> rows,
        boolean truncated,
        Integer updateCount,
        String error,
        long elapsedMillis) {

    public boolean hasResultSet() {
        return columns != null;
    }

    public boolean hasError() {
        return error != null;
    }
}
