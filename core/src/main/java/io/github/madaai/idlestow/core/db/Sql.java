package io.github.madaai.idlestow.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tiny helpers for parameterized JDBC. All SQL in IdleStow uses bound parameters — never string concatenation of values. */
public final class Sql {
    private Sql() { }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int idx = i + 1;
            if (a == null) ps.setNull(idx, Types.NULL);
            else if (a instanceof Integer v) ps.setInt(idx, v);
            else if (a instanceof Long v) ps.setLong(idx, v);
            else if (a instanceof Double v) ps.setDouble(idx, v);
            else if (a instanceof Boolean v) ps.setInt(idx, v ? 1 : 0);
            else if (a instanceof Enum<?> e) ps.setString(idx, e.name());
            else ps.setString(idx, a.toString());
        }
    }

    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    public static long insert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("No generated key for insert");
    }

    public static <T> List<T> list(Connection c, String sql, RowMapper<T> m, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(m.map(rs));
                return out;
            }
        }
    }

    public static <T> Optional<T> one(Connection c, String sql, RowMapper<T> m, Object... args) throws SQLException {
        List<T> l = list(c, sql, m, args);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public static long scalarLong(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Reads a nullable long column. */
    public static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static long getLong(ResultSet rs, String col, long dflt) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? dflt : v;
    }

    public static Double getDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    /** Escapes LIKE wildcards; use with {@code ESCAPE '\'}. */
    public static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
