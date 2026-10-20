package com.example.dbconsole;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs SQL typed into the web page against the configured DataSource.
 *
 * The DataSource is a HikariCP pool that Spring Boot builds from spring.datasource.*,
 * which in turn comes from the DB_* environment variables injected by OpenShift
 * (ConfigMap for host/port/schema, Secret for username/password).
 */
@Service
public class QueryService {

    private final DataSource dataSource;
    private final int maxRows;
    private final int queryTimeoutSeconds;

    public QueryService(DataSource dataSource,
                        @Value("${console.max-rows:500}") int maxRows,
                        @Value("${console.query-timeout-seconds:30}") int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.maxRows = maxRows;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public QueryResult execute(String sql) {
        long start = System.currentTimeMillis();
        String trimmed = stripTrailingSemicolon(sql == null ? "" : sql.trim());
        if (trimmed.isEmpty()) {
            return new QueryResult(sql, null, null, false, null, "Please enter a SQL statement.", 0);
        }

        // Borrow a connection from the pool; closing it returns it to the pool.
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Ask for one extra row so we can tell the user the output was cut off.
            stmt.setMaxRows(maxRows + 1);
            stmt.setQueryTimeout(queryTimeoutSeconds);

            boolean isResultSet = stmt.execute(trimmed);
            if (!isResultSet) {
                return new QueryResult(trimmed, null, null, false, stmt.getUpdateCount(), null, elapsed(start));
            }

            try (ResultSet rs = stmt.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();
                List<String> columns = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == maxRows) {
                        truncated = true;
                        break;
                    }
                    List<String> row = new ArrayList<>(count);
                    for (int i = 1; i <= count; i++) {
                        Object value = rs.getObject(i);
                        row.add(value == null ? null : value.toString());
                    }
                    rows.add(row);
                }
                return new QueryResult(trimmed, columns, rows, truncated, null, null, elapsed(start));
            }
        } catch (SQLException e) {
            return new QueryResult(trimmed, null, null, false, null, describe(e), elapsed(start));
        }
    }

    /** Facts about the live connection, shown on the page so you can see what you are connected to. */
    public Map<String, String> connectionInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            info.put("Status", "Connected");
            info.put("JDBC URL", meta.getURL());
            info.put("DB user", meta.getUserName());
            info.put("Server", meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
            info.put("Driver", meta.getDriverName() + " " + meta.getDriverVersion());
            info.put("Current schema", conn.getCatalog());
        } catch (SQLException e) {
            info.put("Status", "NOT connected");
            info.put("Error", describe(e));
        }
        return info;
    }

    private static String describe(SQLException e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getMessage());
        if (e.getSQLState() != null) {
            sb.append(" [SQLState ").append(e.getSQLState()).append(", code ").append(e.getErrorCode()).append(']');
        }
        // Connection failures usually hide the useful reason (timeout, refused, access denied) in the cause.
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().equals(e.getMessage())) {
            sb.append("\nCaused by: ").append(cause.getMessage());
        }
        return sb.toString();
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
