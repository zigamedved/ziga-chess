import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Projections;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private static final String INDEX_DIR = "indexedFiles";
    private static MongoClient mongoClient;
    private static MongoCollection<org.bson.Document> collection;

    public static void main(String[] args) throws IOException {
        // Creating a Mongo client, "localhost", 27017
        mongoClient = MongoClients.create("TODO"); // add your mongo connection string
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

    static class FenParser implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String authHeader = t.getRequestHeaders().getFirst("Authorization");
            if (authHeader == null || authHeader.length() == 0 || !authHeader.startsWith("Basic ")) {
                t.sendResponseHeaders(401, -1);
                t.close();
            }

            // Get the encoded credentials from the header
            String encodedCredentials = null;
            String credentials = null;
            String username = null;
            String password = null;
            try {
                encodedCredentials = authHeader.substring("Basic ".length());
                // Decode the base64-encoded credentials
                credentials = new String(Base64.getDecoder().decode(encodedCredentials));
                // Extract the username and password from the credentials
                String[] usernameAndPassword = credentials.split(":");
                username = usernameAndPassword[0];
                password = usernameAndPassword[1];
            } catch (Exception ex) {
                // User is not authenticated, send a 401 Unauthorized response
                t.sendResponseHeaders(401, -1);
                t.close();
            }

            if (!username.equals("zigamedved") || !password.equals("skrivnost.1234")) {
                // User is not authenticated, send a 401 Unauthorized response
                t.sendResponseHeaders(401, -1);
                t.close();
            }

            // Get the request body (JSON data)
            InputStreamReader isr = new InputStreamReader(t.getRequestBody(), "utf-8");
            BufferedReader br = new BufferedReader(isr);
            String body = br.readLine().replace("\"", "");
            br.close();
            isr.close();

            Map<String, Float> result = null;
            try {
                result = queryLucene(body);
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Define the list of names to query
            List<String> keysList = new ArrayList<>(result.keySet());

            // Create the filter using the "in" operator to query multiple names
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

            // Process the query results
            List<String> result2 = new ArrayList<>();
            try (MongoCursor<org.bson.Document> cursor = queryResult.iterator()) {
                while (cursor.hasNext()) {
                    org.bson.Document document = cursor.next();
                    String name = document.getString("name");
                    document.append("score", result.get(name));
                    result2.add(document.toJson());
                }
            }
            // Send the response
            sendResponse(t, result2);
        }
    }

    static class PingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "pong";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    private static void sendResponse(HttpExchange t, List<String> result) throws IOException {
        Gson gson = new Gson();
        String jsonResponse = gson.toJson(result);

        t.getResponseHeaders().set("Content-Type", "application/json");
        t.sendResponseHeaders(200, jsonResponse.length());
        OutputStream os = t.getResponseBody();
        os.write(jsonResponse.getBytes());
        os.close();
    }

    public static Map<String, Float> queryLucene(String input) throws Exception {
        IndexSearcher searcher = createSearcher();
        String escapedText = escapeCharacters(input);
        // Search indexed contents using search term
        TopDocs foundDocs = searchInContent(escapedText, searcher);
        // Total found documents
        System.out.println("Total Results :: " + foundDocs.totalHits);

        Map<String, Float> result = new HashMap<>();
        // Let's print out the path of files which have searched term
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
        Directory dir = FSDirectory.open(Paths.get(INDEX_DIR));

        // It is an interface for accessing a point-in-time view of a lucene index
        IndexReader reader = DirectoryReader.open(dir);

        // Index searcher
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());

        return searcher;
    }
}
