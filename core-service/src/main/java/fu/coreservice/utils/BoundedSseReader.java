package fu.coreservice.utils;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/** Reads LF, CRLF or CR lines without buffering an unbounded upstream line. */
public final class BoundedSseReader implements Closeable {
    private final BufferedReader reader;
    private final int maxLineChars;
    private boolean skipLf;

    public BoundedSseReader(Reader reader, int maxLineChars) {
        if (maxLineChars < 1) throw new IllegalArgumentException("Line limit must be positive");
        this.reader = new BufferedReader(reader);
        this.maxLineChars = maxLineChars;
    }

    public String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            if (skipLf) {
                skipLf = false;
                if (value == '\n') continue;
            }
            if (value == '\n') return line.toString();
            if (value == '\r') {
                skipLf = true;
                return line.toString();
            }
            if (line.length() == maxLineChars) throw new IOException("AI SSE line exceeded size limit");
            line.append((char) value);
        }
        return line.isEmpty() ? null : line.toString();
    }

    @Override
    public void close() throws IOException { reader.close(); }
}
