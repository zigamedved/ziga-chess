import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.management.UnixOperatingSystemMXBean;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
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
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChessServerTest {

    @BeforeEach
    void setAuth() {
        AuthFilter.configureAuth("test-user", "test-password");
    }

    @AfterEach
    void clearIndexDirProperty() throws IOException {
        LuceneSearchService.closeSearchResources();
        System.clearProperty("chess.indexDir");
        System.clearProperty("chess.topK");
        System.clearProperty("chess.featureCli");
        System.clearProperty("chess.featurePython");
        System.clearProperty("chess.endgameCli");
        System.clearProperty("chess.endgamePython");
        System.clearProperty("chess.port");
        System.clearProperty("chess.appPrefix");
        AuthFilter.configureAuth(null, null);
    }

    @Test
    void staticFiles_servesTheBoardPage() throws Exception {
        System.setProperty("chess.appPrefix", "");
        StaticFiles handler = new StaticFiles();
        FakeExchange page = new FakeExchange();
        page.setRequestMethod("GET");
        page.setRequestUri("/");
        handler.handle(page);
        assertEquals(200, page.statusCode);
        assertTrue(page.responseHeaders.getFirst("Content-Type").startsWith("text/html"));
        assertTrue(page.responseBody.toString("UTF-8").contains("Analyze Game"));
        assertTrue(page.responseBody.toString("UTF-8").contains("content=\"\""));

        System.setProperty("chess.appPrefix", "/v1/chess");
        FakeExchange prefixed = new FakeExchange();
        prefixed.setRequestMethod("GET");
        prefixed.setRequestUri("/");
        handler.handle(prefixed);
        assertTrue(prefixed.responseBody.toString("UTF-8").contains("content=\"/v1/chess\""));

        FakeExchange script = new FakeExchange();
        script.setRequestMethod("GET");
        script.setRequestUri("/vendor/chess.js");
        handler.handle(script);
        assertEquals(200, script.statusCode);
        assertTrue(script.responseHeaders.getFirst("Content-Type").startsWith("text/javascript"));

        FakeExchange escaped = new FakeExchange();
        escaped.setRequestMethod("GET");
        escaped.setRequestUri("/vendor/../app.js");
        handler.handle(escaped);
        assertEquals(404, escaped.statusCode);
    }

    @Test
    void listenPort_defaultsToPublicPort() {
        assertEquals(8081, chessServer.parsePort(null));
        assertEquals(8081, chessServer.parsePort("  "));
        assertEquals(9090, chessServer.parsePort("9090"));
    }

    @Test
    void listenPort_propertyOverridesEnv() {
        System.setProperty("chess.port", "9091");
        assertEquals(9091, chessServer.listenPort());
    }

    @Test
    void escapeCharacters_escapesLuceneSpecialsUsedByApp() {
        assertEquals("\\!\\?\\-\\+", LuceneSearchService.escapeCharacters("!?-+"));
        assertEquals("plain", LuceneSearchService.escapeCharacters("plain"));
        assertEquals("a\\-b\\+c", LuceneSearchService.escapeCharacters("a-b+c"));
        assertEquals("\\!kh8", LuceneSearchService.escapeCharacters("!kh8"));
        assertEquals("r\\-r\\-8", LuceneSearchService.escapeCharacters("r-r-8"));
        assertEquals("\\!re8", LuceneSearchService.escapeCharacters("!re8"));
    }

    @Test
    void requireAuthConfigured_failsWhenMissing() {
        AuthFilter.configureAuth(null, null);
        assertThrows(IllegalStateException.class, AuthFilter::requireAuthConfigured);
        AuthFilter.configureAuth("u", "");
        assertThrows(IllegalStateException.class, AuthFilter::requireAuthConfigured);
    }

    @Test
    void requireIndexPresent_failsWhenMissing(@TempDir Path tempDir) {
        System.setProperty("chess.indexDir", tempDir.resolve("no-such-index").toString());
        assertThrows(IllegalStateException.class, LuceneSearchService::requireIndexPresent);
    }

    @Test
    void authorize_rejectsMissingHeaderAndDoesNotProceed() throws IOException {
        FakeExchange ex = new FakeExchange();
        assertFalse(AuthFilter.authorize(ex));
        assertEquals(401, ex.statusCode);
    }

    @Test
    void authorize_rejectsWrongPassword() throws IOException {
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", basicAuth("test-user", "wrong"));
        assertFalse(AuthFilter.authorize(ex));
        assertEquals(401, ex.statusCode);
    }

    @Test
    void authorize_acceptsValidCredentials() throws IOException {
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        assertTrue(AuthFilter.authorize(ex));
        assertEquals(-1, ex.statusCode);
    }

    @Test
    void authorize_rejectsMalformedBase64() throws IOException {
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", "Basic !!!not-base64!!!");
        assertFalse(AuthFilter.authorize(ex));
        assertEquals(401, ex.statusCode);
    }

    @Test
    void fenParser_unauthenticatedNeverReachesLucene(@TempDir Path tempDir) throws IOException {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        FenParser handler = new FenParser();
        FakeExchange ex = new FakeExchange();
        ex.setRequestBody("\"Kg1 Re7\"");

        handler.handle(ex);

        assertEquals(401, ex.statusCode);
    }

    @Test
    void fenParser_emptyBodyReturns400() throws IOException {
        FenParser handler = new FenParser();
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

        FenParser handler = new FenParser();
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
        assertEquals("Pb2 Kg1 !re8", FenParser.readFeatureBody(ex));
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

        List<Map<String, Object>> hits = LuceneSearchService.searchSimilar("Kg1 Re7 Dpc");

        assertFalse(hits.isEmpty());
        Map<String, Object> hit = hits.get(0);
        assertEquals("Game#fixture.txt", hit.get("name"));
        assertEquals("Fixture, White", hit.get("White"));
        assertEquals("Fixture, Black", hit.get("Black"));
        assertTrue(((Number) hit.get("score")).floatValue() > 0f);
        assertMatchedTokens(hit, "Kg1", "static");
        assertMatchedTokens(hit, "Dpc", "other");
        @SuppressWarnings("unchecked")
        Map<String, Object> explanation = (Map<String, Object>) hit.get("explanation");
        assertFieldScore(explanation, "dynamic", 0d);
    }

    @Test
    void matchExplanation_sumsWeightNodesAndIgnoresBreakdown() {
        org.apache.lucene.search.Explanation explanation = org.apache.lucene.search.Explanation.match(
                1.7f,
                "sum of:",
                org.apache.lucene.search.Explanation.match(
                        1.0f,
                        "weight(static:Kg1 in 0) [BM25Similarity], result of:",
                        org.apache.lucene.search.Explanation.match(0.4f, "score(freq=1.0), computed as boost * idf * tf")),
                org.apache.lucene.search.Explanation.match(0.5f, "weight(other:Dpc in 0) [BM25Similarity], result of:"),
                org.apache.lucene.search.Explanation.match(0.2f, "weight(static:Re7 in 0) [BM25Similarity], result of:"));

        Map<String, Object> parsed = MatchExplanation.from(explanation);
        assertFieldScore(parsed, "static", 1.2d);
        assertFieldScore(parsed, "other", 0.5d);
        assertFieldScore(parsed, "dynamic", 0d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tokens = (List<Map<String, Object>>) parsed.get("tokens");
        assertEquals("Kg1", tokens.get(0).get("token"));
        assertEquals(3, tokens.size());
    }

    @Test
    void queryLucene_returnsHitFromFixtureIndex(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        Map<String, Float> hits = LuceneSearchService.queryLucene("Kg1 Re7 Dpc");

        assertTrue(hits.containsKey("Game#fixture.txt"), "hits=" + hits);
        assertTrue(hits.get("Game#fixture.txt") > 0f);
    }

    @Test
    void topK_defaultsAndHonorsProperty() {
        System.clearProperty("chess.topK");
        if (System.getenv("LUCENE_TOP_K") == null) {
            assertEquals(20, LuceneSearchService.topK());
        }
        System.setProperty("chess.topK", "7");
        if (System.getenv("LUCENE_TOP_K") == null) {
            assertEquals(7, LuceneSearchService.topK());
        }
        System.setProperty("chess.topK", "0");
        if (System.getenv("LUCENE_TOP_K") == null) {
            assertEquals(20, LuceneSearchService.topK());
        }
    }

    @Test
    void searchSimilar_limitsToTopK(@TempDir Path tempDir) throws Exception {
        buildMultiDocIndex(tempDir, 5);
        System.setProperty("chess.indexDir", tempDir.toString());
        System.setProperty("chess.topK", "2");

        List<Map<String, Object>> hits = LuceneSearchService.searchSimilar("Kg1");

        assertEquals(2, hits.size());
    }

    @Test
    void searchSimilar_reusesOneReaderWithoutFdGrowth(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        LuceneSearchService.searchSimilar("Kg1");
        IndexReader reader = LuceneSearchService.sharedReader();
        int refs = reader.getRefCount();
        long fdsBefore = openFileDescriptorCount();

        for (int i = 0; i < 40; i++) {
            LuceneSearchService.searchSimilar("Kg1 Re7");
        }

        assertSame(reader, LuceneSearchService.sharedReader());
        assertEquals(refs, LuceneSearchService.sharedReader().getRefCount());
        long fdsAfter = openFileDescriptorCount();
        if (fdsBefore >= 0 && fdsAfter >= 0) {
            assertTrue(
                    fdsAfter - fdsBefore <= 8,
                    "open FDs grew by " + (fdsAfter - fdsBefore) + " across 40 searches");
        }

        // Same MatchAllDocsQuery both ways, so this is open/close vs acquire/release
        // (not query parsing). A 1-doc fixture makes open() cheap; FD reuse is the check.
        long openEachNanos = timeOpenReaderPerQuery(tempDir, 40);
        long sharedNanos = timeSharedSearcher(40);
        System.out.println(
                "LUCENE_BENCH openEachMs="
                        + (openEachNanos / 1_000_000.0)
                        + " sharedMs="
                        + (sharedNanos / 1_000_000.0)
                        + " queries=40");
    }

    @Test
    void searchSimilar_reopensWhenIndexDirChanges(@TempDir Path first, @TempDir Path second) throws Exception {
        buildFixtureIndex(first);
        buildFixtureIndex(second);
        System.setProperty("chess.indexDir", first.toString());
        LuceneSearchService.searchSimilar("Kg1");
        IndexReader firstReader = LuceneSearchService.sharedReader();

        System.setProperty("chess.indexDir", second.toString());
        LuceneSearchService.searchSimilar("Kg1");
        assertNotSame(firstReader, LuceneSearchService.sharedReader());
    }

    @Test
    void searchSimilar_refreshesAfterCommit(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());
        assertTrue(LuceneSearchService.searchSimilar("brandnewtoken").isEmpty());

        try (FSDirectory directory = FSDirectory.open(tempDir);
             IndexWriter writer = new IndexWriter(
                     directory, new IndexWriterConfig(new LuceneSearchService.MyAnalyzer()))) {
            Document doc = new Document();
            doc.add(new TextField("static", "brandnewtoken", Field.Store.NO));
            doc.add(new TextField("other", "extra", Field.Store.NO));
            doc.add(new TextField("dynamic", "later", Field.Store.NO));
            doc.add(new StringField("name", "Game#new.txt", Field.Store.YES));
            doc.add(new StoredField("White", "New, White"));
            doc.add(new StoredField("Black", "New, Black"));
            doc.add(new StoredField("Result", "*"));
            doc.add(new StoredField("PGN", "1. e4 *"));
            doc.add(new StoredField("PV1", ""));
            doc.add(new StoredField("PV2", ""));
            doc.add(new StoredField("endgameFEN", "4k3/8/8/8/8/8/8/4K3 w - - 0 1"));
            writer.addDocument(doc);
            writer.commit();
        }

        List<Map<String, Object>> hits = LuceneSearchService.searchSimilar("brandnewtoken");
        assertEquals(1, hits.size());
        assertEquals("Game#new.txt", hits.get(0).get("name"));
    }

    @Test
    void queryLucene_escapesAndMatchesDynamicBangToken(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());

        Map<String, Float> hits = LuceneSearchService.queryLucene("!re8");

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
        List<Map<String, Object>> hits = LuceneSearchService.searchSimilar("Ke1");
        assertEquals(1, hits.size());
        assertEquals("Game#a.txt", hits.get(0).get("name"));
        assertEquals("W", hits.get(0).get("White"));
    }

    @Test
    void indexBuilder_updateAddsAndReplacesWithoutDroppingOthers(@TempDir Path tempDir) throws Exception {
        Path index = tempDir.resolve("index");
        Path original = tempDir.resolve("original.ndjson");
        Files.writeString(original, docLine("Game#a.txt", "W", "Ke1 ke8"));
        assertEquals(1, IndexBuilder.buildIndex(original, index));

        System.setProperty("chess.indexDir", index.toString());
        assertEquals("W", LuceneSearchService.searchSimilar("Ke1").get(0).get("White"));

        Path extra = tempDir.resolve("extra.ndjson");
        Files.writeString(extra, docLine("Game#a.txt", "Changed", "Ke1 ke8")
                + docLine("Game#b.txt", "New", "onlytoken"));
        assertEquals(2, IndexBuilder.updateIndex(extra, index));

        List<Map<String, Object>> kept = LuceneSearchService.searchSimilar("Ke1");
        assertEquals(1, kept.size());
        assertEquals("Changed", kept.get(0).get("White"));
        List<Map<String, Object>> added = LuceneSearchService.searchSimilar("onlytoken");
        assertEquals(1, added.size());
        assertEquals("Game#b.txt", added.get(0).get("name"));

        LuceneSearchService.closeSearchResources();
        assertEquals(1, IndexBuilder.buildIndex(original, index));
        assertTrue(LuceneSearchService.searchSimilar("onlytoken").isEmpty());
        assertEquals("W", LuceneSearchService.searchSimilar("Ke1").get(0).get("White"));
    }

    private static String docLine(String name, String white, String features) {
        return "{\"name\":\"" + name + "\",\"White\":\"" + white + "\",\"Black\":\"B\",\"Result\":\"1-0\","
                + "\"PGN\":\"1. e4\",\"PV1\":\"\",\"PV2\":\"\","
                + "\"endgameFEN\":\"4k3/8/8/8/8/8/8/4K3 w - - 0 1\","
                + "\"static\":\"" + features + "\",\"other\":\"\",\"dynamic\":\"\"}\n";
    }

    @Test
    void searchSimilar_emptyFilterKeepsRankAndBoundsDropGames(@TempDir Path tempDir) throws Exception {
        try (FSDirectory directory = FSDirectory.open(tempDir);
             IndexWriter writer = new IndexWriter(
                     directory,
                     new IndexWriterConfig(new LuceneSearchService.MyAnalyzer()))) {
            writer.addDocument(IndexBuilder.toDocument(doc(
                    "Game#high.txt", "0-1", "2700", "2700", "Kg1 Re7 Dpc")));
            writer.addDocument(IndexBuilder.toDocument(doc(
                    "Game#low.txt", "1-0", "2000", "1800", "Kg1")));
            writer.addDocument(IndexBuilder.toDocument(doc(
                    "Game#none.txt", "1-0", "", "", "Kg1")));
            writer.commit();
        }
        System.setProperty("chess.indexDir", tempDir.toString());

        List<Map<String, Object>> open = LuceneSearchService.searchSimilar("Kg1 Re7");
        List<Map<String, Object>> same = LuceneSearchService.searchSimilar("Kg1 Re7", SearchFilter.none());
        assertEquals(namesOf(open), namesOf(same));
        assertEquals("Game#high.txt", open.get(0).get("name"));
        assertEquals(3, open.size());

        List<Map<String, Object>> whiteWins = LuceneSearchService.searchSimilar(
                "Kg1", SearchFilter.from(jsonObj("{\"result\":\"1-0\"}")));
        assertEquals(2, whiteWins.size());
        assertTrue(namesOf(whiteWins).contains("Game#low.txt"));
        assertTrue(namesOf(whiteWins).contains("Game#none.txt"));
        assertFalse(namesOf(whiteWins).contains("Game#high.txt"));

        List<Map<String, Object>> strong = LuceneSearchService.searchSimilar(
                "Kg1", SearchFilter.from(jsonObj("{\"minElo\":2500}")));
        assertEquals(List.of("Game#high.txt"), namesOf(strong));
        assertEquals("2700", strong.get(0).get("WhiteElo"));

        List<Map<String, Object>> low = LuceneSearchService.searchSimilar(
                "Kg1", SearchFilter.from(jsonObj("{\"maxElo\":1900}")));
        assertEquals(List.of("Game#low.txt"), namesOf(low));

        List<Map<String, Object>> rated = LuceneSearchService.searchSimilar(
                "Kg1", SearchFilter.from(jsonObj("{\"minElo\":0}")));
        assertEquals(2, rated.size());
        assertFalse(namesOf(rated).contains("Game#none.txt"));
    }

    @Test
    void analyseHandler_appliesResultAndRejectsABadEloBound(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());
        AnalyseHandler handler = new AnalyseHandler(fen ->
                new FeatureCli.Result(fen, "Kg1 Re7 Dpc", "pv-one", "pv-two"));
        String fen = "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25";

        FakeExchange wins = authorised("{\"FEN\":\"" + fen + "\",\"result\":\"1-0\"}");
        handler.handle(wins);
        assertEquals(200, wins.statusCode);
        com.google.gson.JsonObject winJson = new com.google.gson.JsonParser()
                .parse(wins.responseBody.toString("UTF-8")).getAsJsonObject();
        assertEquals(1, winJson.getAsJsonArray("similarGames").size());
        assertEquals("Game#fixture.txt", winJson.getAsJsonArray("similarGames").get(0).getAsJsonObject().get("name").getAsString());

        FakeExchange losses = authorised("{\"FEN\":\"" + fen + "\",\"result\":\"0-1\"}");
        handler.handle(losses);
        assertEquals(200, losses.statusCode);
        com.google.gson.JsonObject lossJson = new com.google.gson.JsonParser()
                .parse(losses.responseBody.toString("UTF-8")).getAsJsonObject();
        assertEquals(0, lossJson.getAsJsonArray("similarGames").size());

        FakeExchange unrated = authorised("{\"FEN\":\"" + fen + "\",\"minElo\":2000}");
        handler.handle(unrated);
        assertEquals(200, unrated.statusCode);
        assertTrue(unrated.responseBody.toString("UTF-8").contains("\"similarGames\":[]"));

        FakeExchange badResult = authorised("{\"FEN\":\"" + fen + "\",\"result\":\"mate\"}");
        handler.handle(badResult);
        assertEquals(400, badResult.statusCode);
        assertTrue(badResult.responseBody.toString("UTF-8").contains("bad_request"));

        FakeExchange badRange = authorised("{\"FEN\":\"" + fen + "\",\"minElo\":2400,\"maxElo\":2000}");
        handler.handle(badRange);
        assertEquals(400, badRange.statusCode);
        assertTrue(badRange.responseBody.toString("UTF-8").contains("minElo is greater than maxElo"));
    }

    private static FakeExchange authorised(String body) {
        FakeExchange exchange = new FakeExchange();
        exchange.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        exchange.setRequestBody(body);
        return exchange;
    }

    private static com.google.gson.JsonObject jsonObj(String text) {
        return new com.google.gson.JsonParser().parse(text).getAsJsonObject();
    }

    private static com.google.gson.JsonObject doc(String name, String result, String whiteElo, String blackElo, String features) {
        return jsonObj("{\"name\":\"" + name + "\",\"White\":\"W\",\"Black\":\"B\",\"Result\":\"" + result
                + "\",\"WhiteElo\":\"" + whiteElo + "\",\"BlackElo\":\"" + blackElo
                + "\",\"PGN\":\"1. e4\",\"PV1\":\"\",\"PV2\":\"\","
                + "\"endgameFEN\":\"8/8/8/8/8/8/4k3/4K3 w - - 0 1\","
                + "\"static\":\"" + features + "\",\"other\":\"\",\"dynamic\":\"\"}");
    }

    private static List<String> namesOf(List<Map<String, Object>> hits) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            names.add(String.valueOf(hit.get("name")));
        }
        return names;
    }

    private static long timeOpenReaderPerQuery(Path indexDir, int queries) throws IOException {
        long start = System.nanoTime();
        for (int i = 0; i < queries; i++) {
            try (Directory dir = FSDirectory.open(indexDir);
                 IndexReader reader = DirectoryReader.open(dir)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                searcher.search(new MatchAllDocsQuery(), 20);
            }
        }
        return System.nanoTime() - start;
    }

    private static long timeSharedSearcher(int queries) throws IOException {
        LuceneSearchService.ensureSearcher();
        long start = System.nanoTime();
        for (int i = 0; i < queries; i++) {
            LuceneSearchService.searcherManager.maybeRefresh();
            IndexSearcher searcher = LuceneSearchService.searcherManager.acquire();
            try {
                searcher.search(new MatchAllDocsQuery(), 20);
            } finally {
                LuceneSearchService.searcherManager.release(searcher);
            }
        }
        return System.nanoTime() - start;
    }

    private static long openFileDescriptorCount() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        return -1;
    }

    private static void buildMultiDocIndex(Path indexDir, int count) throws IOException {
        try (FSDirectory directory = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(
                     directory,
                     new IndexWriterConfig(new LuceneSearchService.MyAnalyzer()))) {
            for (int i = 0; i < count; i++) {
                Document doc = new Document();
                doc.add(new TextField("static", "Kg1 shared", Field.Store.NO));
                doc.add(new TextField("other", "token" + i, Field.Store.NO));
                doc.add(new TextField("dynamic", "!re8", Field.Store.NO));
                doc.add(new StringField("name", "Game#" + i + ".txt", Field.Store.YES));
                doc.add(new StoredField("White", "W" + i));
                doc.add(new StoredField("Black", "B" + i));
                doc.add(new StoredField("Result", "*"));
                doc.add(new StoredField("PGN", "1. e4 *"));
                doc.add(new StoredField("PV1", ""));
                doc.add(new StoredField("PV2", ""));
                doc.add(new StoredField("endgameFEN", "4k3/8/8/8/8/8/8/4K3 w - - 0 1"));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    @Test
    void analyseText_matchesPythonJoin() {
        Map<String, Object> game = new java.util.LinkedHashMap<>();
        game.put("name", "Game#fixture.txt");
        game.put("score", 1.5d);
        String text = AnalyseText.format("pv-one", "pv-two", java.util.List.of(game));
        assertTrue(text.startsWith("Analysed lines of input game:\n\nPV1: \npv-one\n\nPV2: \npv-two"));
        assertTrue(text.contains("\nSimilar games:"));
        assertTrue(text.contains("\nGame #0"));
        assertTrue(text.contains("name: Game#fixture.txt"));
        assertTrue(text.contains("score: 1.5"));
        assertEquals("99.0", AnalyseText.stringify(99.0d));
    }

    @Test
    void similarPgn_wrapsMovetextAndKeepsHeadedGames() {
        Map<String, Object> movetext = new java.util.LinkedHashMap<>();
        movetext.put("name", "Game \"one\"");
        movetext.put("white", "A");
        movetext.put("black", "B");
        movetext.put("result", "1-0");
        movetext.put("endgameFen", "8/8/8/8/8/8/4k3/4K3 w - - 0 1");
        movetext.put("pgn", "1. e4 1-0");

        Map<String, Object> headed = new java.util.LinkedHashMap<>();
        headed.put("pgn", "[Event \"already\"]\n\n1. d4 1-0");

        Map<String, Object> empty = new java.util.LinkedHashMap<>();
        empty.put("pgn", "  ");

        String pgn = SimilarPgn.format(java.util.List.of(movetext, empty, headed));
        assertTrue(pgn.startsWith("[Event \"Game \\\"one\\\"\"]\n[White \"A\"]\n"));
        assertTrue(pgn.contains("[SetUp \"1\"]\n\n1. e4 1-0\n\n[Event \"already\"]"));
        assertTrue(pgn.endsWith("1. d4 1-0\n"));
    }

    @Test
    void analyseHandler_requiresAuthAndFen(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());
        AnalyseHandler handler = new AnalyseHandler(fen -> {
            throw new AssertionError("CLI should not run");
        });

        FakeExchange missingAuth = new FakeExchange();
        missingAuth.setRequestBody("{\"FEN\":\"8/8/8/8/8/8/8/8 w - - 0 1\"}");
        handler.handle(missingAuth);
        assertEquals(401, missingAuth.statusCode);
        assertTrue(missingAuth.responseBody.toString("UTF-8").contains("unauthorized"));

        FakeExchange missingFen = new FakeExchange();
        missingFen.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        missingFen.setRequestBody("{}");
        handler.handle(missingFen);
        assertEquals(400, missingFen.statusCode);
        assertTrue(missingFen.responseBody.toString("UTF-8").contains("bad_request"));
    }

    @Test
    void analyseHandler_searchesFeaturesFromCli(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());
        AnalyseHandler handler = new AnalyseHandler(fen ->
                new FeatureCli.Result(fen, "Kg1 Re7 Dpc", "pv-one", "pv-two"));

        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        ex.setRequestBody("{\"FEN\":\"r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25\"}");
        handler.handle(ex);

        assertEquals(200, ex.statusCode);
        assertTrue(ex.responseHeaders.getFirst("Content-Type").startsWith("application/json"));
        String body = ex.responseBody.toString("UTF-8");
        com.google.gson.JsonObject json = new com.google.gson.JsonParser().parse(body).getAsJsonObject();
        assertEquals("r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25", json.get("fen").getAsString());
        assertEquals(1, json.getAsJsonArray("principalVariations").get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals("pv-one", json.getAsJsonArray("principalVariations").get(0).getAsJsonObject().get("pgn").getAsString());
        assertEquals("pv-two", json.getAsJsonArray("principalVariations").get(1).getAsJsonObject().get("pgn").getAsString());
        com.google.gson.JsonObject game = json.getAsJsonArray("similarGames").get(0).getAsJsonObject();
        assertEquals(0, game.get("rank").getAsInt());
        assertEquals("Game#fixture.txt", game.get("name").getAsString());
        assertTrue(game.get("score").isJsonPrimitive() && game.get("score").getAsJsonPrimitive().isNumber());
        assertTrue(json.getAsJsonObject("meta").get("tookMs").getAsLong() >= 0);
        assertEquals(12, json.getAsJsonObject("meta").get("engineDepth").getAsInt());
        assertEquals(8, json.getAsJsonObject("meta").get("limitDepth").getAsInt());
        String similarPgn = json.get("similarPgn").getAsString();
        assertTrue(similarPgn.contains("[White \"Fixture, White\"]"));
        assertTrue(similarPgn.contains("[FEN \"r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25\"]"));
        assertTrue(similarPgn.contains("1. e4 e5 1-0"));
        assertTrue(game.getAsJsonObject("explanation").getAsJsonArray("tokens").size() > 0);

        FakeExchange plain = new FakeExchange();
        plain.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        plain.requestHeaders.add("Accept", "text/plain");
        plain.setRequestBody("{\"FEN\":\"r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25\"}");
        handler.handle(plain);
        assertEquals(200, plain.statusCode);
        assertTrue(plain.responseHeaders.getFirst("Content-Type").startsWith("text/plain"));
        String text = plain.responseBody.toString("UTF-8");
        assertTrue(text.contains("PV1: \npv-one"));
        assertTrue(text.contains("PV2: \npv-two"));
        String plainGame = text.substring(text.indexOf("Game #0"));
        assertFieldOrder(plainGame, "name:", "endgameFEN:", "White:", "Black:", "Result:", "PGN:", "PV1:", "PV2:", "score:");
        assertFalse(plainGame.contains("explanation"));
    }

    @Test
    void analyseHandler_invalidFenFromCliIs400() throws Exception {
        AnalyseHandler handler = new AnalyseHandler(fen -> {
            throw new FeatureCli.InvalidFenException("Invalid FEN");
        });
        FakeExchange ex = new FakeExchange();
        ex.requestHeaders.add("Authorization", basicAuth("test-user", "test-password"));
        ex.setRequestBody("{\"FEN\":\"not-a-fen\"}");
        handler.handle(ex);
        assertEquals(400, ex.statusCode);
        assertTrue(ex.responseBody.toString("UTF-8").contains("invalid_fen"));
    }

    @Test
    void analyseHandler_pgnUsesDetectedEndgameFen(@TempDir Path tempDir) throws Exception {
        buildFixtureIndex(tempDir);
        System.setProperty("chess.indexDir", tempDir.toString());
        Path script = tempDir.resolve("fake_endgame.py");
        Files.writeString(script, ""
                + "import json, sys\n"
                + "text = sys.stdin.read()\n"
                + "if \"BAD\" in text:\n"
                + "    sys.stderr.write(\"Invalid PGN\\n\")\n"
                + "    sys.exit(2)\n"
                + "if \"OPENING\" in text:\n"
                + "    sys.stderr.write(\"No rook endgame in this game\\n\")\n"
                + "    sys.exit(3)\n"
                + "json.dump({\"fen\": \"8/8/8/8/8/8/4k3/4K3 w - - 0 1\", \"ply\": 1}, sys.stdout)\n");
        System.setProperty("chess.endgameCli", script.toString());
        java.util.List<String> seen = new java.util.ArrayList<>();
        AnalyseHandler handler = new AnalyseHandler(fen -> {
            seen.add(fen);
            return new FeatureCli.Result(fen, "Kg1 Re7 Dpc", "pv-one", "pv-two");
        });

        FakeExchange ok = authorised("{\"PGN\":\"1. Rxa2\"}");
        handler.handle(ok);
        assertEquals(200, ok.statusCode);
        com.google.gson.JsonObject json = new com.google.gson.JsonParser()
                .parse(ok.responseBody.toString("UTF-8")).getAsJsonObject();
        assertEquals("8/8/8/8/8/8/4k3/4K3 w - - 0 1", json.get("fen").getAsString());
        assertEquals(1, json.get("endgamePly").getAsInt());
        assertEquals("8/8/8/8/8/8/4k3/4K3 w - - 0 1", seen.get(0));
        assertEquals(1, json.getAsJsonArray("similarGames").size());

        FakeExchange bad = authorised("{\"PGN\":\"BAD\"}");
        handler.handle(bad);
        assertEquals(400, bad.statusCode);
        assertTrue(bad.responseBody.toString("UTF-8").contains("invalid_pgn"));

        FakeExchange none = authorised("{\"PGN\":\"OPENING\"}");
        handler.handle(none);
        assertEquals(400, none.statusCode);
        assertTrue(none.responseBody.toString("UTF-8").contains("no_endgame"));
    }

    @Test
    void featureCli_runsScriptAndParsesJson(@TempDir Path tempDir) throws Exception {
        Path script = tempDir.resolve("fake_cli.py");
        Files.writeString(script, ""
                + "import json, sys\n"
                + "json.dump({"
                + "\"fen\": sys.argv[1],"
                + "\"features\": \"Kg1 Re7 Dpc\","
                + "\"pv1\": \"pv-one\","
                + "\"pv2\": \"pv-two\""
                + "}, sys.stdout)\n"
                + "sys.stdout.write(\"\\n\")\n");
        System.setProperty("chess.featureCli", script.toString());

        FeatureCli.Result result = FeatureCli.run("8/8/8/8/8/8/4k3/4K3 w - - 0 1");
        assertEquals("8/8/8/8/8/8/4k3/4K3 w - - 0 1", result.fen);
        assertEquals("Kg1 Re7 Dpc", result.features);
        assertEquals("pv-one", result.pv1);
        assertEquals("pv-two", result.pv2);
    }

    @SuppressWarnings("unchecked")
    private static void assertMatchedTokens(Map<String, Object> hit, String token, String field) {
        Map<String, Object> explanation = (Map<String, Object>) hit.get("explanation");
        List<Map<String, Object>> tokens = (List<Map<String, Object>>) explanation.get("tokens");
        boolean found = false;
        for (Map<String, Object> row : tokens) {
            if (token.equals(row.get("token")) && field.equals(row.get("field"))) {
                found = true;
            }
        }
        assertTrue(found, "tokens=" + tokens);
    }

    @SuppressWarnings("unchecked")
    private static void assertFieldScore(Map<String, Object> explanation, String field, double expected) {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) explanation.get("fields");
        for (Map<String, Object> row : fields) {
            if (field.equals(row.get("field"))) {
                assertEquals(expected, ((Number) row.get("score")).doubleValue(), 0.0001d);
                return;
            }
        }
        assertTrue(false, "missing field " + field);
    }

    private static void assertFieldOrder(String body, String... labels) {
        int previous = -1;
        for (String label : labels) {
            int at = body.indexOf(label);
            assertTrue(at > previous, "expected " + label + " after earlier fields in " + body);
            previous = at;
        }
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
                     new IndexWriterConfig(new LuceneSearchService.MyAnalyzer()))) {
            Document doc = new Document();
            doc.add(new TextField("static", "Kg1 kg8 Re7 rf8 Pa5", Field.Store.NO));
            doc.add(new TextField("other", "Dpc ORe r-r-8", Field.Store.NO));
            doc.add(new TextField("dynamic", "!re8 !kh8", Field.Store.NO));
            doc.add(new StringField("name", "Game#fixture.txt", Field.Store.YES));
            doc.add(new StoredField("White", "Fixture, White"));
            doc.add(new StoredField("Black", "Fixture, Black"));
            doc.add(new StringField("Result", "1-0", Field.Store.YES));
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
        private String requestMethod = "POST";
        private URI requestUri = URI.create("/position");

        void setRequestMethod(String requestMethod) {
            this.requestMethod = requestMethod;
        }

        void setRequestUri(String requestUri) {
            this.requestUri = URI.create(requestUri);
        }

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
            return requestUri;
        }

        @Override
        public String getRequestMethod() {
            return requestMethod;
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
