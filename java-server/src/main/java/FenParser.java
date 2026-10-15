import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** POST /position: auth, parse the feature string, return Lucene hits. */
public class FenParser implements HttpHandler {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Gson GSON = new Gson();

    @Override
    public void handle(HttpExchange t) throws IOException {
        if (!AuthFilter.authorize(t)) {
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
            hits = LuceneSearchService.searchSimilar(body);
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
        sendJsonError(t, status, message, status == 400 ? "bad_request" : "error");
    }

    static void sendJsonError(HttpExchange t, int status, String message, String code) throws IOException {
        Map<String, String> payload = new HashMap<>();
        payload.put("error", message);
        payload.put("code", code);
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().set("Content-Type", "application/json");
        t.sendResponseHeaders(status, bytes.length);
        OutputStream os = t.getResponseBody();
        os.write(bytes);
        os.close();
    }
}
