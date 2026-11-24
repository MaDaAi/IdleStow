package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.util.CsvWriter;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvWriterTest {
    @Test
    void plainFieldsAreNotQuoted() {
        assertEquals("a,b,c", CsvWriter.row(List.of("a", "b", "c")));
    }

    @Test
    void quotesCommasQuotesAndNewlines() {
        assertEquals("\"a,b\"", CsvWriter.field("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.field("say \"hi\""));
        assertEquals("\"line1\nline2\"", CsvWriter.field("line1\nline2"));
        assertEquals("\"cr\rlf\"", CsvWriter.field("cr\rlf"));
    }

    @Test
    void nullBecomesEmptyAndUnicodeIsKept() {
        assertEquals("", CsvWriter.field(null));
        String uni = "C:\\Users\\J\u00fcrgen\\\u6587\u4ef6.txt";
        assertEquals(uni, CsvWriter.field(uni));
        assertEquals("\" padded \"", CsvWriter.field(" padded "));
    }

    @Test
    void writesHeaderAndRowsWithCrlf() throws Exception {
        StringWriter w = new StringWriter();
        CsvWriter.write(w, List.of("h1", "h2"), List.of(List.of("1", "x,y"), List.of("2", "")));
        assertEquals("h1,h2\r\n1,\"x,y\"\r\n2,\r\n", w.toString());
    }
}
