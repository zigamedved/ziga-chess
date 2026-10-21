import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** GET / and the chessboard page. API routes stay on their own contexts. */
public final class StaticFiles implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String resource = resourceFor(exchange.getRequestURI().getPath());
        if (resource == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] body = read(resource);
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(resource));
        exchange.sendResponseHeaders(200, "HEAD".equals(method) ? -1 : body.length);
        if (!"HEAD".equals(method)) {
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        } else {
            exchange.close();
        }
    }

    static String resourceFor(String path) {
        if (path == null || path.contains("..")) {
            return null;
        }
        return switch (path) {
            case "/", "/index.html" -> "/web/index.html";
            case "/app.css" -> "/web/app.css";
            case "/app.js" -> "/web/app.js";
            case "/vendor/chess.js" -> "/web/vendor/chess.js";
            default -> null;
        };
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = StaticFiles.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        }
    }
}
