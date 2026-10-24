import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON body for {@code POST /analyse}. Field names are the public contract in
 * {@code openapi.yaml}. Lucene stored names stay on the plain-text path.
 */
public final class AnalyseResponse {
    private static final Gson GSON = new Gson();

    private AnalyseResponse() {
    }

    public static String toJson(String fen, FeatureCli.Result extracted, List<Map<String, Object>> hits, long tookMs) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("fen", fen);

        List<Map<String, Object>> variations = new ArrayList<>();
        variations.add(variation(1, extracted.pv1));
        variations.add(variation(2, extracted.pv2));
        root.put("principalVariations", variations);

        List<Map<String, Object>> games = new ArrayList<>();
        int rank = 0;
        for (Map<String, Object> hit : hits) {
            games.add(game(rank, hit));
            rank++;
        }
        root.put("similarGames", games);
        root.put("similarPgn", SimilarPgn.format(games));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("engineDepth", extracted.engineDepth);
        meta.put("limitDepth", extracted.limitDepth);
        meta.put("tookMs", tookMs);
        root.put("meta", meta);
        return GSON.toJson(root);
    }

    private static Map<String, Object> variation(int id, String pgn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("pgn", pgn == null ? "" : pgn);
        return row;
    }

    private static Map<String, Object> game(int rank, Map<String, Object> hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", rank);
        row.put("name", text(hit, "name"));
        row.put("white", text(hit, "White"));
        row.put("black", text(hit, "Black"));
        row.put("result", text(hit, "Result"));
        row.put("endgameFen", text(hit, "endgameFEN"));
        row.put("pgn", text(hit, "PGN"));
        row.put("pv1", text(hit, "PV1"));
        row.put("pv2", text(hit, "PV2"));
        Object score = hit.get("score");
        row.put("score", score instanceof Number ? score : 0);
        return row;
    }

    private static String text(Map<String, Object> hit, String key) {
        Object value = hit.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
