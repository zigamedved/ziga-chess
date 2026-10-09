import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChessServerTest {

    @AfterEach
    void clearIndexDirProperty() {
        System.clearProperty("chess.indexDir");
    }

    @Test
    void escapeCharacters_escapesLuceneSpecialsUsedByApp() {
        assertEquals("\\!\\?\\-\\+", chessServer.escapeCharacters("!?-+"));
        assertEquals("plain", chessServer.escapeCharacters("plain"));
        assertEquals("a\\-b\\+c", chessServer.escapeCharacters("a-b+c"));
        // Feature tokens from the Python side often include these characters.
        assertEquals("\\!kh8", chessServer.escapeCharacters("!kh8"));
        assertEquals("r\\-r\\-8", chessServer.escapeCharacters("r-r-8"));
        assertEquals("\\!re8", chessServer.escapeCharacters("!re8"));
    }

    @Test
    void pingHandler_returnsPong() throws IOException {
        chessServer.PingHandler handler = new chessServer.PingHandler();
        FakeExchange ex = new FakeExchange();

        handler.handle(ex);

        assertEquals(200, ex.statusCode);
        assertEquals("pong", ex.responseBody.toString("UTF-8"));
    }

    @Test
    void queryLucene_returnsHitFromFixtureIndex(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        Map<String, Float> hits = chessServer.queryLucene("Kg1 Re7 Dpc");

        assertTrue(hits.containsKey("Game#fixture.txt"), "hits=" + hits);
        assertTrue(hits.get("Game#fixture.txt") > 0f);
    }

    @Test
    void queryLucene_escapesAndMatchesDynamicBangToken(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        Map<String, Float> hits = chessServer.queryLucene("!re8");

        assertTrue(hits.containsKey("Game#fixture.txt"), "hits=" + hits);
    }

    private static void buildFixtureIndex(Path indexDir) throws IOException {
        try (FSDirectory directory = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(
                     directory,
                     new IndexWriterConfig(new chessServer.MyAnalyzer()))) {
            Document doc = new Document();
            doc.add(new TextField("static", "Kg1 kg8 Re7 rf8 Pa5", Field.Store.YES));
            doc.add(new TextField("other", "Dpc ORe r-r-8", Field.Store.YES));
            doc.add(new TextField("dynamic", "!re8 !kh8", Field.Store.YES));
            // Production path parsing: split on '\' and take index 2.
            doc.add(new StringField(
                    "path",
                    "indexedFiles\\games\\Game#fixture.txt",
                    Field.Store.YES));
            writer.addDocument(doc);
            writer.commit();
        }
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int statusCode = -1;

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/ping");
        }

        @Override
        public String getRequestMethod() {
            return "GET";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {
            this.statusCode = rCode;
        }

        @Override
        public int getResponseCode() {
            return statusCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(0);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream i, OutputStream o) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
