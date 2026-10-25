import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field and token contributions for one similar game. Built from Lucene's
 * BM25 explanation so the index schema stays unchanged.
 */
public final class MatchExplanation {
    static final int TOP_TOKENS = 5;
    private static final Pattern WEIGHT = Pattern.compile(
            "weight\\((static|other|dynamic):([^\\s]+) in \\d+\\)");
    private static final String[] FIELDS = {"static", "other", "dynamic"};

    private MatchExplanation() {
    }

    public static Map<String, Object> forDoc(IndexSearcher searcher, Query query, int docId) throws IOException {
        return from(searcher.explain(query, docId));
    }

    static Map<String, Object> from(Explanation explanation) {
        Map<String, Double> best = new LinkedHashMap<>();
        Map<String, String> fieldOf = new LinkedHashMap<>();
        Map<String, String> tokenOf = new LinkedHashMap<>();
        walk(explanation, best, fieldOf, tokenOf);

        Map<String, Double> fieldScores = new LinkedHashMap<>();
        for (String field : FIELDS) {
            fieldScores.put(field, 0d);
        }
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (Map.Entry<String, Double> entry : best.entrySet()) {
            String field = fieldOf.get(entry.getKey());
            fieldScores.put(field, fieldScores.get(field) + entry.getValue());
            Map<String, Object> token = new LinkedHashMap<>();
            token.put("field", field);
            token.put("token", tokenOf.get(entry.getKey()));
            token.put("score", entry.getValue());
            tokens.add(token);
        }
        tokens.sort(Comparator
                .comparingDouble((Map<String, Object> row) -> -(((Number) row.get("score")).doubleValue()))
                .thenComparing(row -> String.valueOf(row.get("field")))
                .thenComparing(row -> String.valueOf(row.get("token"))));
        if (tokens.size() > TOP_TOKENS) {
            tokens = new ArrayList<>(tokens.subList(0, TOP_TOKENS));
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        for (String field : FIELDS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", field);
            row.put("score", fieldScores.get(field));
            fields.add(row);
        }
        Map<String, Object> explanationMap = new LinkedHashMap<>();
        explanationMap.put("fields", fields);
        explanationMap.put("tokens", tokens);
        return explanationMap;
    }

    private static void walk(Explanation explanation, Map<String, Double> best,
                             Map<String, String> fieldOf, Map<String, String> tokenOf) {
        if (explanation == null) {
            return;
        }
        Matcher matcher = WEIGHT.matcher(explanation.getDescription());
        if (matcher.find()) {
            String field = matcher.group(1);
            String token = matcher.group(2);
            String key = field + "\0" + token;
            double score = explanation.getValue().doubleValue();
            Double previous = best.get(key);
            if (previous == null || score > previous) {
                best.put(key, score);
                fieldOf.put(key, field);
                tokenOf.put(key, token);
            }
        }
        for (Explanation detail : explanation.getDetails()) {
            walk(detail, best, fieldOf, tokenOf);
        }
    }
}
