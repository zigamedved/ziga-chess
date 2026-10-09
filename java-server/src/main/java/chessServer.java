import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoClients;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;

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
    private static final Gson GSON = new Gson();

    private static MongoClient mongoClient;
    private static MongoCollection<org.bson.Document> collection;

    /** Set from AUTH_USERNAME / AUTH_PASSWORD (or via configureAuth in tests). */
    static String authUsername;
    static String authPassword;

    /** Overridable for tests via -Dchess.indexDir=/path/to/index */
    static String indexDir() {
        return System.getProperty("chess.indexDir", "indexedFiles");
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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static void main(String[] args) throws IOException {
        configureAuth(System.getenv("AUTH_USERNAME"), System.getenv("AUTH_PASSWORD"));
        requireAuthConfigured();

        // Creating a Mongo client, "localhost", 27017
        String mongoUri = System.getenv("MONGO_URI");
        if (isBlank(mongoUri)) {
            throw new IllegalStateException(
                    "MONGO_URI must be set via the environment. See .env.example.");
        }
        mongoClient = MongoClients.create(mongoUri);
        MongoDatabase database = mongoClient.getDatabase("chessGames");
        collection = database.getCollection("games");

        String envValue = System.getenv("APP_PREFIX");
        envValue = envValue != null ? envValue : "";

        System.out.println("env variable: " + envValue);

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

        try {
            String encodedCredentials = authHeader.substring("Basic ".length()).trim();
            String credentials = new String(
                    Base64.getDecoder().decode(encodedCredentials),
                    StandardCharsets.UTF_8);
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
        } catch (Exception ex) {
            sendUnauthorized(t);
            return false;
        }
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
        // Legacy/plain path: strip surrounding quotes if present.
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

            Map<String, Float> result;
            try {
                result = queryLucene(body);
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonError(t, 500, "Lucene search failed");
                return;
            }

            if (collection == null) {
                sendJsonError(t, 503, "MongoDB is not configured");
                return;
            }

            List<String> keysList = new ArrayList<>(result.keySet());
            org.bson.Document filter = new org.bson.Document("name", new org.bson.Document("$in", keysList));
            org.bson.Document projection = new org.bson.Document();
            projection.append("_id", 0);
            projection.append("name", 1);
            projection.append("endgameFEN", 1);
            projection.append("White", 1);
            projection.append("Black", 1);
            projection.append("Result", 1);
            projection.append("PGN", 1);
            projection.append("PV1", 1);
            projection.append("PV2", 1);

            FindIterable<org.bson.Document> queryResult = collection.find(filter).projection(projection);

            List<String> result2 = new ArrayList<>();
            try (MongoCursor<org.bson.Document> cursor = queryResult.iterator()) {
                while (cursor.hasNext()) {
                    org.bson.Document document = cursor.next();
                    String name = document.getString("name");
                    document.append("score", result.get(name));
                    result2.add(document.toJson());
                }
            }
            sendResponse(t, result2);
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

    public static Map<String, Float> queryLucene(String input) throws Exception {
        IndexSearcher searcher = createSearcher();
        String escapedText = escapeCharacters(input);
        TopDocs foundDocs = searchInContent(escapedText, searcher);
        System.out.println("Total Results :: " + foundDocs.totalHits);

        Map<String, Float> result = new HashMap<>();
        for (ScoreDoc sd : foundDocs.scoreDocs) {
            Document d = searcher.doc(sd.doc);
            String path = d.get("path").split("\\\\")[2];
            result.put(path, sd.score);
        }
        return result;
    }

    public static String escapeCharacters(String input) {
        return input.replace("!", "\\!")
                .replace("?", "\\?")
                .replace("-", "\\-")
                .replace("+", "\\+");
    }

    private static TopDocs searchInContent(String textToFind, IndexSearcher searcher) throws Exception {
        MyAnalyzer analyzer = new MyAnalyzer();
        String[] fields = { "static", "other", "dynamic" };
        Map<String, Float> boosts = new HashMap<>();
        boosts.put("static", 1.0f);
        boosts.put("other", 1.0f);
        boosts.put("dynamic", 1.0f);
        MultiFieldQueryParser queryParser = new MultiFieldQueryParser(fields, analyzer, boosts);
        Query query = queryParser.parse(textToFind);
        return searcher.search(query, 1000);
    }

    private static IndexSearcher createSearcher() throws IOException {
        Directory dir = FSDirectory.open(Paths.get(indexDir()));
        IndexReader reader = DirectoryReader.open(dir);
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
        return searcher;
    }
}
