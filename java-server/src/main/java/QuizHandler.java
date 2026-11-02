import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /quiz} lists positions. {@code POST /quiz} checks a move or
 * reveals the solution. Similar games stay on {@code POST /analyse}.
 */
public class QuizHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Gson GSON = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!AuthFilter.authorize(exchange)) {
            return;
        }
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            writeJson(exchange, 200, GSON.toJson(publicList()));
            return;
        }
        if (!"POST".equals(method)) {
            FenParser.sendJsonError(exchange, 405, "Method not allowed", "error");
            return;
        }
        final JsonObject body;
        try {
            body = readBody(exchange);
        } catch (FenParser.BadRequestException e) {
            FenParser.sendJsonError(exchange, 400, e.getMessage());
            return;
        }
        String id = text(body, "id");
        if (id == null) {
            FenParser.sendJsonError(exchange, 400, "id is required (string)");
            return;
        }
        QuizBank.Position position = QuizBank.find(id);
        if (position == null) {
            FenParser.sendJsonError(exchange, 404, "Unknown quiz position", "not_found");
            return;
        }
        boolean showSolution = bool(body, "showSolution");
        String san = text(body, "san");
        if (!showSolution && san == null) {
            FenParser.sendJsonError(exchange, 400, "A move or showSolution is required");
            return;
        }
        boolean correct = san != null
                && QuizBank.normalizeSan(san).equals(QuizBank.normalizeSan(position.solution));
        boolean revealed = showSolution || correct;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", position.id);
        payload.put("title", position.title);
        payload.put("fen", position.fen);
        payload.put("correct", correct);
        payload.put("revealed", revealed);
        if (revealed) {
            payload.put("solution", position.solution);
        }
        writeJson(exchange, 200, GSON.toJson(payload));
    }

    private static List<Map<String, Object>> publicList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QuizBank.Position position : QuizBank.positions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", position.id);
            row.put("title", position.title);
            row.put("fen", position.fen);
            rows.add(row);
        }
        return rows;
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException, FenParser.BadRequestException {
        byte[] raw = FenParser.readLimited(exchange.getRequestBody(), MAX_BODY_BYTES);
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            throw new FenParser.BadRequestException("id is required (string)");
        }
        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(text);
        } catch (JsonSyntaxException ex) {
            throw new FenParser.BadRequestException("id is required (string)");
        }
        if (!parsed.isJsonObject()) {
            throw new FenParser.BadRequestException("id is required (string)");
        }
        return parsed.getAsJsonObject();
    }

    private static String text(JsonObject obj, String name) {
        if (!obj.has(name) || obj.get(name).isJsonNull() || !obj.get(name).isJsonPrimitive()) {
            return null;
        }
        if (!obj.get(name).getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = obj.get(name).getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean bool(JsonObject obj, String name) {
        return obj.has(name)
                && obj.get(name).isJsonPrimitive()
                && obj.get(name).getAsJsonPrimitive().isBoolean()
                && obj.get(name).getAsBoolean();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}
