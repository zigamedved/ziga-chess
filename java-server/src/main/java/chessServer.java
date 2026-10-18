import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * Process entry. Wires {@link AuthFilter}, {@link FenParser}, {@link AnalyseHandler},
 * and {@link LuceneSearchService} onto the HTTP server.
 */
public class chessServer {
    public static void main(String[] args) throws IOException {
        AuthFilter.configureAuth(System.getenv("AUTH_USERNAME"), System.getenv("AUTH_PASSWORD"));
        AuthFilter.requireAuthConfigured();
        LuceneSearchService.requireIndexPresent();
        LuceneSearchService.ensureSearcher();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                LuceneSearchService.closeSearchResources();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));

        String envValue = System.getenv("APP_PREFIX");
        envValue = envValue != null ? envValue : "";

        System.out.println("env variable: " + envValue);
        System.out.println("Lucene index: " + Paths.get(LuceneSearchService.indexDir()).toAbsolutePath());

        int port = listenPort();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(envValue + "/position", new FenParser());
        server.createContext(envValue + "/analyse", new AnalyseHandler());
        server.createContext("/ping", new PingHandler());
        if (!envValue.isEmpty()) {
            server.createContext(envValue + "/ping", new PingHandler());
        }

        server.setExecutor(null);
        server.start();

        System.out.println("Server started on 0.0.0.0:" + port);
    }

    /** {@code chess.port} property, then {@code PORT}, then 8081. */
    static int listenPort() {
        String property = System.getProperty("chess.port");
        if (property != null && !property.trim().isEmpty()) {
            return parsePort(property);
        }
        return parsePort(System.getenv("PORT"));
    }

    static int parsePort(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 8081;
        }
        final int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("PORT is not an integer: " + raw);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("PORT out of range: " + raw);
        }
        return port;
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
}
