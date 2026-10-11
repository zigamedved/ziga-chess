import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChessServerTest {

    @BeforeEach
    void setAuth() {
        chessServer.configureAuth("test-user", "test-password");
    }

    @AfterEach
    void clearIndexDirProperty() {
        System.clearProperty("chess.indexDir");
        System.clearProperty("chess.topK");
        chessServer.configureAuth(null, null);
    }

    @Test
    void escapeCharacters_escapesLuceneSpecialsUsedByApp() {
        assertEquals("\\!\\?\\-\\+", chessServer.escapeCharacters("!?-+"));
        assertEquals("plain", chessServer.escapeCharacters("plain"));
        assertEquals("a\\-b\\+c", chessServer.escapeCharacters("a-b+c"));
        assertEquals("\\!kh8", chessServer.escapeCharacters("!kh8"));
        assertEquals("r\\-r\\-8", chessServer.escapeCharacters("r-r-8"));
        assertEquals("\\!re8", chessServer.escapeCharacters("!re8"));
    }

    @Test
    void requireAuthConfigured_failsWhenMissing() {
        chessServer.configureAuth(null, null);
        assertThrows(IllegalStateException.class, chessServer::requireAuthConfigured);
        chessServer.configureAuth("u", "");
        assertThrows(IllegalStateException.class, chessServer::requireAuthConfigured);
    }

    @Test
    void requireIndexPresent_failsWhenMissing(@TempDir Path tempDir) {
        System.setProperty("chess.indexDir", tempDir.resolve("no-such-index").toString());
        assertThrows(IllegalStateException.class, chessServer::requireIndexPresent);
    }

    @Test
    void authorize_rejectsMissingHeaderAndDoesNotProceed() throws IOException {
        FakeExchange ex = new FakeExchange();
        assertFalse(chessServer.authorize(ex));
        assertEquals(401, ex.statusCode);
    }

    @Test
    void authorize_rejectsWrongPassword() throws IOException {
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", basicAuth("test-user", "wrong"));
        assertFalse(chessServer.authorize(ex));
        assertEquals(401, ex.statusCode);
    }

    @Test
    void authorize_acceptsValidCredentials() throws IOException {
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        assertTrue(chessServer.authorize(ex));
        assertEquals(-1, ex.statusCode);
    }

    @Test
    void authorize_rejectsMalformedBase64() throws IOException {
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", "Basic !!!not-base64!!!");
        assertFalse(chessServer.authorize(ex));
        assertEquals(401, ex.statusCode);
    }

    @Test
    void fenParser_unauthenticatedNeverReachesLucene(@TempDir Path tempDir) throws IOException {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        chessServer.FenParser handler = new chessServer.FenParser();
        FakeExchange ex = new FakeExchange();
        ex.setRequestBody("\"Kg1 Re7\"");

        handler.handle(ex);

        assertEquals(401, ex.statusCode);
    }

    @Test
    void fenParser_emptyBodyReturns400() throws IOException {
        chessServer.FenParser handler = new chessServer.FenParser();
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        ex.setRequestBody("");

        handler.handle(ex);

        assertEquals(400, ex.statusCode);
        assertTrue(ex.responseBody.toString("UTF-8").contains("empty"));
    }

    @Test
    void fenParser_returnsHydratedHitsWithoutMongo(@TempDir Path tempDir) throws IOException {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        chessServer.FenParser handler = new chessServer.FenParser();
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        ex.setRequestBody("\"Kg1 Re7 Dpc\"");

        handler.handle(ex);

        assertEquals(200, ex.statusCode);
        String body = ex.responseBody.toString("UTF-8");
        assertTrue(body.contains("Game#fixture.txt"), body);
        assertTrue(body.contains("endgameFEN"), body);
        assertTrue(body.contains("White"), body);
        assertTrue(body.contains("score"), body);
    }

    @Test
    void readFeatureBody_parsesJsonStringPayload() throws Exception {
        FakeExchange ex = new FakeExchange();
        ex.setRequestBody("\"Pb2 Kg1 !re8\"");
        assertEquals("Pb2 Kg1 !re8", chessServer.readFeatureBody(ex));
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
    void searchSimilar_returnsStoredMetadata(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        List<Map<String, Object>> hits = chessServer.searchSimilar("Kg1 Re7 Dpc");

        assertFalse(hits.isEmpty());
        Map<String, Object> hit = hits.get(0);
        assertEquals("Game#fixture.txt", hit.get("name"));
        assertEquals("Fixture, White", hit.get("White"));
        assertEquals("Fixture, Black", hit.get("Black"));
        assertTrue(((Number) hit.get("score")).floatValue() > 0f);
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

    @Test
    void indexBuilder_roundTrip(@TempDir Path tempDir) throws Exception {
        Path docs = tempDir.resolve("docs.ndjson");
        Files.writeString(
                docs,
                "{\"name\":\"Game#a.txt\",\"White\":\"W\",\"Black\":\"B\",\"Result\":\"1-0\","
                        + "\"PGN\":\"1. e4\",\"PV1\":\"\",\"PV2\":\"\","
                        + "\"endgameFEN\":\"4k3/8/8/8/8/8/8/4K3 w - - 0 1\","
                        + "\"static\":\"Ke1 ke8\",\"other\":\"Kcc\",\"dynamic\":\"!Ke2\"}\n");
        Path index = tempDir.resolve("index");
        long n = IndexBuilder.buildIndex(docs, index);
        assertEquals(1, n);

        System.setProperty("chess.indexDir", index.toString());
        List<Map<String, Object>> hits = chessServer.searchSimilar("Ke1");
        assertEquals(1, hits.size());
        assertEquals("Game#a.txt", hits.get(0).get("name"));
        assertEquals("W", hits.get(0).get("White"));
    }

    private static String basicAuth(String user, String pass) {
        String token = Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static void buildFixtureIndex(Path indexDir) throws IOException {
        try (FSDirectory directory = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(
                     directory,
                     new IndexWriterConfig(new chessServer.MyAnalyzer()))) {
            Document doc = new Document();
            doc.add(new TextField("static", "Kg1 kg8 Re7 rf8 Pa5", Field.Store.NO));
            doc.add(new TextField("other", "Dpc ORe r-r-8", Field.Store.NO));
            doc.add(new TextField("dynamic", "!re8 !kh8", Field.Store.NO));
            doc.add(new StringField("name", "Game#fixture.txt", Field.Store.YES));
            doc.add(new StoredField("White", "Fixture, White"));
            doc.add(new StoredField("Black", "Fixture, Black"));
            doc.add(new StoredField("Result", "1-0"));
            doc.add(new StoredField("PGN", "1. e4 e5 1-0"));
            doc.add(new StoredField("PV1", "pv1"));
            doc.add(new StoredField("PV2", "pv2"));
            doc.add(new StoredField(
                    "endgameFEN",
                    "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25"));
            writer.addDocument(doc);
            writer.commit();
        }
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private byte[] requestBodyBytes = new byte[0];
        private int statusCode = -1;

        void setRequestBody(String body) {
            this.requestBodyBytes = body.getBytes(StandardCharsets.UTF_8);
        }

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
            return URI.create("/position");
        }

        @Override
        public String getRequestMethod() {
            return "POST";
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
            return new ByteArrayInputStream(requestBodyBytes);
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
