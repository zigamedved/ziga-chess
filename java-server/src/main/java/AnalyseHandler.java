import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code POST /analyse} on the JVM: one auth check, feature CLI, in-process Lucene.
 * Default body is JSON ({@link AnalyseResponse}). {@code Accept: text/plain}
 * returns the thesis plain-text body.
 */
public class AnalyseHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 64 * 1024;

    /** Tests supply a stub. Production runs {@link FeatureCli}. */
    interface FeatureSource {
        FeatureCli.Result extract(String fen) throws Exception;
    }

    private final FeatureSource features;

    public AnalyseHandler() {
        this(FeatureCli::run);
    }

    AnalyseHandler(FeatureSource features) {
        this.features = features;
    }

    @Override
    public void handle(HttpExchange t) throws IOException {
        if (!AuthFilter.authorize(t)) {
            return;
        }

        final RequestedAnalyse request;
        try {
            request = readRequest(t);
        } catch (FenParser.BadRequestException e) {
            FenParser.sendJsonError(t, 400, e.getMessage());
            return;
        }
        final String fen;
        final Integer endgamePly;
        if (request.pgn != null) {
            try {
                EndgameCli.Result found = EndgameCli.run(request.pgn);
                fen = found.fen;
                endgamePly = found.ply;
            } catch (EndgameCli.InvalidPgnException e) {
                FenParser.sendJsonError(t, 400, e.getMessage(), "invalid_pgn");
                return;
            } catch (EndgameCli.NoEndgameException e) {
                FenParser.sendJsonError(t, 400, e.getMessage(), "no_endgame");
                return;
            } catch (Exception e) {
                e.printStackTrace();
                FenParser.sendJsonError(t, 500, "Endgame CLI failed");
                return;
            }
        } else {
            fen = request.fen;
            endgamePly = null;
        }

        long started = System.nanoTime();
        final FeatureCli.Result extracted;
        try {
            extracted = features.extract(fen);
        } catch (FeatureCli.InvalidFenException e) {
            FenParser.sendJsonError(t, 400, "Invalid FEN!", "invalid_fen");
            return;
        } catch (Exception e) {
            e.printStackTrace();
            FenParser.sendJsonError(t, 500, "Feature CLI failed");
            return;
        }

        final List<Map<String, Object>> hits;
        try {
            hits = LuceneSearchService.searchSimilar(extracted.features, request.filter);
        } catch (Exception e) {
            e.printStackTrace();
            FenParser.sendJsonError(t, 500, "Lucene search failed");
            return;
        }

        long tookMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        final String body;
        final String contentType;
        if (wantsPlainText(t)) {
            body = AnalyseText.format(extracted.pv1, extracted.pv2, hits);
            contentType = "text/plain; charset=utf-8";
        } else {
            body = AnalyseResponse.toJson(fen, extracted, hits, tookMs, endgamePly);
            contentType = "application/json; charset=utf-8";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().set("Content-Type", contentType);
        t.sendResponseHeaders(200, bytes.length);
        OutputStream os = t.getResponseBody();
        os.write(bytes);
        os.close();
    }

    static final class RequestedAnalyse {
        final String fen;
        final String pgn;
        final SearchFilter filter;

        RequestedAnalyse(String fen, String pgn, SearchFilter filter) {
            this.fen = fen;
            this.pgn = pgn;
            this.filter = filter;
        }
    }

    static RequestedAnalyse readRequest(HttpExchange t) throws IOException, FenParser.BadRequestException {
        byte[] raw = FenParser.readLimited(t.getRequestBody(), MAX_BODY_BYTES);
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            throw new FenParser.BadRequestException("FEN is required (string)");
        }
        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(text);
        } catch (JsonSyntaxException ex) {
            throw new FenParser.BadRequestException("FEN is required (string)");
        }
        if (!parsed.isJsonObject()) {
            throw new FenParser.BadRequestException("FEN is required (string)");
        }
        JsonObject obj = parsed.getAsJsonObject();
        String fen = optionalText(obj, "FEN");
        String pgn = optionalText(obj, "PGN");
        if (fen != null) {
            return new RequestedAnalyse(fen, null, SearchFilter.from(obj));
        }
        if (pgn != null) {
            return new RequestedAnalyse(null, pgn, SearchFilter.from(obj));
        }
        throw new FenParser.BadRequestException("FEN is required (string)");
    }

    /** Blank and missing fields are absent. A non-string value is a bad request. */
    private static String optionalText(JsonObject obj, String name) throws FenParser.BadRequestException {
        if (!obj.has(name) || obj.get(name).isJsonNull()) {
            return null;
        }
        if (!obj.get(name).isJsonPrimitive() || !obj.get(name).getAsJsonPrimitive().isString()) {
            throw new FenParser.BadRequestException(name + " is required (string)");
        }
        String value = obj.get(name).getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    /** Thesis clients send Accept text/plain. A wildcard Accept stays on JSON. */
    static boolean wantsPlainText(HttpExchange t) {
        String accept = t.getRequestHeaders().getFirst("Accept");
        if (accept == null || accept.trim().isEmpty()) {
            return false;
        }
        String lower = accept.toLowerCase(Locale.ROOT);
        return lower.contains("text/plain") && !lower.contains("application/json");
    }
}
