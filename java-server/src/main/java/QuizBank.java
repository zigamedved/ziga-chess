import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rook-endgame exercises seeded from {@code results/DvoretskyEndgameManual}.
 * Each solution is the first move of that file's PV1. The list endpoint does
 * not return the solution.
 */
final class QuizBank {
    private static final Gson GSON = new Gson();
    private static List<Position> positions;

    private QuizBank() {
    }

    static final class Position {
        final String id;
        final String title;
        final String fen;
        final String solution;

        Position(String id, String title, String fen, String solution) {
            this.id = id;
            this.title = title;
            this.fen = fen;
            this.solution = solution;
        }
    }

    static List<Position> positions() {
        if (positions == null) {
            positions = Collections.unmodifiableList(load());
        }
        return positions;
    }

    static Position find(String id) {
        for (Position position : positions()) {
            if (position.id.equals(id)) {
                return position;
            }
        }
        return null;
    }

    /** Drop a leading move number such as {@code 1.} or {@code 1...}. */
    static String normalizeSan(String san) {
        if (san == null) {
            return "";
        }
        return san.trim().replaceFirst("^\\d+\\.+\\s*", "");
    }

    private static List<Position> load() {
        InputStream in = QuizBank.class.getResourceAsStream("/quiz/dvoretsky.json");
        if (in == null) {
            throw new IllegalStateException("quiz set missing: /quiz/dvoretsky.json");
        }
        List<Row> rows = GSON.fromJson(
                new InputStreamReader(in, StandardCharsets.UTF_8),
                new TypeToken<List<Row>>() { }.getType());
        List<Position> loaded = new ArrayList<>();
        for (Row row : rows) {
            if (row == null || blank(row.id) || blank(row.fen) || blank(row.solution)) {
                throw new IllegalStateException("quiz row is missing id, fen, or solution");
            }
            String title = blank(row.title) ? row.id : row.title;
            loaded.add(new Position(row.id, title, row.fen, row.solution));
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("quiz set is empty");
        }
        return loaded;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class Row {
        String id;
        String title;
        String fen;
        String solution;
    }
}
