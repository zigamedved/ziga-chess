import java.util.List;
import java.util.Map;

/**
 * Multi-game PGN for the similar-game set. Movetext-only rows gain headers.
 * A game that already starts with {@code [} is copied through.
 */
public final class SimilarPgn {
    private SimilarPgn() {
    }

    public static String format(List<Map<String, Object>> games) {
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> game : games) {
            String body = text(game, "pgn").trim();
            if (body.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("\n\n");
            }
            if (body.startsWith("[")) {
                out.append(body);
                continue;
            }
            String name = text(game, "name");
            out.append("[Event \"").append(escape(name.isEmpty() ? "similar" : name)).append("\"]\n");
            out.append("[White \"").append(escape(text(game, "white"))).append("\"]\n");
            out.append("[Black \"").append(escape(text(game, "black"))).append("\"]\n");
            out.append("[Result \"").append(escape(text(game, "result"))).append("\"]\n");
            String fen = text(game, "endgameFen");
            if (!fen.isEmpty()) {
                out.append("[FEN \"").append(escape(fen)).append("\"]\n");
                out.append("[SetUp \"1\"]\n");
            }
            out.append('\n').append(body);
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        return out.toString();
    }

    private static String text(Map<String, Object> game, String key) {
        Object value = game.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }
}
