package fu.coreservice.utils;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class BoundedSseReaderTest {
    @Test
    void readsMixedLineEndingsBlankLinesAndUnterminatedLastLine() throws Exception {
        try (var reader = new BoundedSseReader(new StringReader("a\r\n\r\nb\rc\nlast"), 4)) {
            assertEquals("a", reader.readLine());
            assertEquals("", reader.readLine());
            assertEquals("b", reader.readLine());
            assertEquals("c", reader.readLine());
            assertEquals("last", reader.readLine());
            assertNull(reader.readLine());
        }
    }

    @Test
    void acceptsExactlyTheLimitWithLfCrLfOrEof() throws Exception {
        for (String ending : new String[]{"\n", "\r\n", "\r", ""}) {
            try (var reader = new BoundedSseReader(new StringReader("abcd" + ending), 4)) {
                assertEquals("abcd", reader.readLine());
                assertNull(reader.readLine());
            }
        }
    }

    @Test
    void rejectsOversizedLineEvenWithoutTerminator() throws Exception {
        for (String ending : new String[]{"\n", "\r\n", ""}) {
            try (var reader = new BoundedSseReader(new StringReader("abcde" + ending), 4)) {
                assertEquals("AI SSE line exceeded size limit",
                        assertThrows(IOException.class, reader::readLine).getMessage());
            }
        }
    }

    @Test
    void closesUnderlyingReader() throws Exception {
        var source = new StringReader("text");
        new BoundedSseReader(source, 4).close();
        assertThrows(IOException.class, () -> source.read());
    }
}
