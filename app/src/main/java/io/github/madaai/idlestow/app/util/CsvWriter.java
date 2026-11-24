package io.github.madaai.idlestow.app.util;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** Minimal RFC 4180 CSV writer (CRLF line ends; fields quoted when they contain a comma, quote or line break). */
public final class CsvWriter {
    private CsvWriter() { }

    public static String field(String value) {
        if (value == null) return "";
        boolean quote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                quote = true;
                break;
            }
        }
        if (!quote && !value.isEmpty() && (value.startsWith(" ") || value.endsWith(" "))) quote = true;
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    public static String row(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(field(fields.get(i)));
        }
        return sb.toString();
    }

    public static void write(Writer out, List<String> header, Iterable<List<String>> rows) throws IOException {
        out.write(row(header));
        out.write("\r\n");
        for (List<String> r : rows) {
            out.write(row(r));
            out.write("\r\n");
        }
    }
}
