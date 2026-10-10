import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.similarities.BM25Similarity;

public class chessServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int DEFAULT_TOP_K = 50;
    private static final Gson GSON = new Gson();

    /** Set from AUTH_USERNAME / AUTH_PASSWORD (or via configureAuth in tests). */
    static String authUsername;
    static String authPassword;

    /** Overridable for tests via -Dchess.indexDir=/path/to/index */
    static String indexDir() {
        String fromEnv = System.getenv("LUCENE_INDEX_DIR");
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv;
        }
        return System.getProperty("chess.indexDir", "indexedFiles");
    }

    static int topK() {
        String raw = System.getenv("LUCENE_TOP_K");
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getProperty("chess.topK", Integer.toString(DEFAULT_TOP_K));
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : DEFAULT_TOP_K;
        } catch (NumberFormatException ex) {
            return DEFAULT_TOP_K;
        }
    }

    static void configureAuth(String username, String password) {
        authUsername = username;
        authPassword = password;
    }

    static void requireAuthConfigured() {
        if (isBlank(authUsername) || isBlank(authPassword)) {
            throw new IllegalStateException(
                    "AUTH_USERNAME and AUTH_PASSWORD must be set via the environment. "
                            + "See .env.example — hardcoded credentials have been removed.");
        }
    }

    static void requireIndexPresent() {
        Path dir = Paths.get(indexDir());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "Lucene index directory missing: " + dir.toAbsolutePath()
                            + ". Build it with `make index` (see README).");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static void main(String[] args) throws IOException {
        configureAuth(System.getenv("AUTH_USERNAME"), System.getenv("AUTH_PASSWORD"));
        requireAuthConfigured();
        requireIndexPresent();

        String envValue = System.getenv("APP_PREFIX");
        envValue = envValue != null ? envValue : "";

        System.out.println("env variable: " + envValue);
        System.out.println("Lucene index: " + Paths.get(indexDir()).toAbsolutePath());

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext(envValue + "/position", new FenParser());
        server.createContext("/ping", new PingHandler());
        if (!envValue.isEmpty()) {
            server.createContext(envValue + "/ping", new PingHandler());
        }

        server.setExecutor(null);
        server.start();

        System.out.println("Server started on 0.0.0.0:8080");
    }

    public static class MyAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer tokenizer = new WhitespaceTokenizer();
            return new TokenStreamComponents(tokenizer);
        }
    }

    /**
     * Validates Basic auth. Returns true when authorized.
     * On failure, sends 401 and returns false — callers must return immediately.
     */
    static boolean authorize(HttpExchange t) throws IOException {
        requireAuthConfigured();
        String authHeader = t.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || authHeader.isEmpty() || !authHeader.startsWith("Basic ")) {
            sendUnauthorized(t);
            return false;
        }

        String encodedCredentials = authHeader.substring("Basic ".length()).trim();
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedCredentials);
        } catch (IllegalArgumentException ex) {
            sendUnauthorized(t);
            return false;
        }

        String credentials = new String(decoded, StandardCharsets.UTF_8);
        int colon = credentials.indexOf(':');
        if (colon < 0) {
            sendUnauthorized(t);
            return false;
        }
        String username = credentials.substring(0, colon);
        String password = credentials.substring(colon + 1);
        if (!authUsername.equals(username) || !authPassword.equals(password)) {
            sendUnauthorized(t);
            return false;
        }
        return true;
    }

    static void sendUnauthorized(HttpExchange t) throws IOException {
        t.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"ziga-chess\"");
        t.sendResponseHeaders(401, -1);
        t.close();
    }

    /**
     * Reads at most MAX_BODY_BYTES and parses the Python client payload:
     * {@code json.dumps(feature_string)} → a JSON string, or raw plain text.
     */
    static String readFeatureBody(HttpExchange t) throws IOException, BadRequestException {
        byte[] rawBytes = readLimited(t.getRequestBody(), MAX_BODY_BYTES);
        String raw = new String(rawBytes, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            throw new BadRequestException("Request body must not be empty");
        }
        try {
            String parsed = GSON.fromJson(raw, String.class);
            if (parsed != null && !parsed.trim().isEmpty()) {
                return parsed.trim();
            }
        } catch (JsonSyntaxException ignored) {
            // Fall through to treating the body as plain feature text.
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.isEmpty()) {
            throw new BadRequestException("Feature string must not be empty");
        }
        return raw;
    }

    static byte[] readLimited(InputStream in, int maxBytes) throws IOException, BadRequestException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new BadRequestException("Request body exceeds " + maxBytes + " bytes");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    static class BadRequestException extends Exception {
        BadRequestException(String message) {
            super(message);
        }
    }

    static class FenParser implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!authorize(t)) {
                return;
            }

            final String body;
            try {
                body = readFeatureBody(t);
            } catch (BadRequestException e) {
                sendJsonError(t, 400, e.getMessage());
                return;
            }

            List<Map<String, Object>> hits;
            try {
                hits = searchSimilar(body);
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonError(t, 500, "Lucene search failed");
                return;
            }

            // Keep wire format expected by python-server: JSON array of JSON strings.
            List<String> encoded = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                encoded.add(GSON.toJson(hit));
            }
            sendResponse(t, encoded);
        }
    }

    static class PingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "pong";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
    }

    private static void sendResponse(HttpExchange t, List<String> result) throws IOException {
        String jsonResponse = GSON.toJson(result);
        t.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, bytes.length);
        OutputStream os = t.getResponseBody();
        os.write(bytes);
        os.close();
    }

    static void sendJsonError(HttpExchange t, int status, String message) throws IOException {
        Map<String, String> payload = new HashMap<>();
        payload.put("error", message);
        payload.put("code", status == 400 ? "bad_request" : "error");
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().set("Content-Type", "application/json");
        t.sendResponseHeaders(status, bytes.length);
        OutputStream os = t.getResponseBody();
        os.write(bytes);
        os.close();
    }

    /**
     * BM25 search over static/other/dynamic; hydrate hits from Lucene stored fields
     * (no Mongo round-trip).
     */
    public static List<Map<String, Object>> searchSimilar(String input) throws Exception {
        IndexSearcher searcher = createSearcher();
        String escapedText = escapeCharacters(input);
        TopDocs foundDocs = searchInContent(escapedText, searcher, topK());
        System.out.println("Total Results :: " + foundDocs.totalHits);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ScoreDoc sd : foundDocs.scoreDocs) {
            Document d = searcher.doc(sd.doc);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", nullToEmpty(d.get("name")));
            row.put("endgameFEN", nullToEmpty(d.get("endgameFEN")));
            row.put("White", nullToEmpty(d.get("White")));
            row.put("Black", nullToEmpty(d.get("Black")));
            row.put("Result", nullToEmpty(d.get("Result")));
            row.put("PGN", nullToEmpty(d.get("PGN")));
            row.put("PV1", nullToEmpty(d.get("PV1")));
            row.put("PV2", nullToEmpty(d.get("PV2")));
            row.put("score", sd.score);
            result.add(row);
        }
        return result;
    }

    /** @deprecated Prefer {@link #searchSimilar(String)}; kept for older unit tests. */
    public static Map<String, Float> queryLucene(String input) throws Exception {
        Map<String, Float> scores = new HashMap<>();
        for (Map<String, Object> row : searchSimilar(input)) {
            Object name = row.get("name");
            Object score = row.get("score");
            if (name != null && score instanceof Number) {
                scores.put(name.toString(), ((Number) score).floatValue());
            }
        }
        return scores;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static String escapeCharacters(String input) {
        return input.replace("!", "\\!")
                .replace("?", "\\?")
                .replace("-", "\\-")
                .replace("+", "\\+");
    }

    private static TopDocs searchInContent(String textToFind, IndexSearcher searcher, int limit)
            throws Exception {
        MyAnalyzer analyzer = new MyAnalyzer();
        String[] fields = { "static", "other", "dynamic" };
        Map<String, Float> boosts = new HashMap<>();
        boosts.put("static", 1.0f);
        boosts.put("other", 1.0f);
        boosts.put("dynamic", 1.0f);
        MultiFieldQueryParser queryParser = new MultiFieldQueryParser(fields, analyzer, boosts);
        Query query = queryParser.parse(textToFind);
        return searcher.search(query, limit);
    }

    private static IndexSearcher createSearcher() throws IOException {
        Directory dir = FSDirectory.open(Paths.get(indexDir()));
        IndexReader reader = DirectoryReader.open(dir);
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
        return searcher;
    }
}
