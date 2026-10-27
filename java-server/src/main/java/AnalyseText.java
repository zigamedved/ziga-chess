import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plain-text {@code /analyse} body. The join matches
 * {@code chessapp.response.format_analyse_text}.
 */
public final class AnalyseText {
    private AnalyseText() {
    }

    public static String format(String pv1, String pv2, List<Map<String, Object>> games) {
        List<String> parts = new ArrayList<>();
        parts.add(String.join("\n\n",
                "Analysed lines of input game:",
                "PV1: \n" + pv1,
                "PV2: \n" + pv2));
        parts.add("\nSimilar games:");
        int idx = 0;
        for (Map<String, Object> game : games) {
            StringBuilder fields = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Object> entry : game.entrySet()) {
                if ("explanation".equals(entry.getKey())
                        || "WhiteElo".equals(entry.getKey())
                        || "BlackElo".equals(entry.getKey())) {
                    continue;
                }
                if (!first) {
                    fields.append('\n');
                }
                first = false;
                fields.append(entry.getKey()).append(": ").append(stringify(entry.getValue()));
            }
            parts.add("\nGame #" + idx);
            parts.add(fields.toString());
            idx++;
        }
        return String.join("\n\n", parts);
    }

    /** Python ``str`` of a JSON number: {@code 99.0}, {@code 12.5}. */
    static String stringify(Object value) {
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1.0e15) {
                return String.format(Locale.US, "%.1f", d);
            }
            return Double.toString(d);
        }
        return value == null ? "" : String.valueOf(value);
    }
}
