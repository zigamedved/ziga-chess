import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code python -m chessapp.endgame}. The PGN is written to stdin, and
 * that stream is closed before stdout is read. Tests point
 * {@code chess.endgameCli} at a stand-in script.
 */
public final class EndgameCli {
    private static final long TIMEOUT_SECONDS = 30;

    private EndgameCli() {
    }

    public static final class Result {
        public final String fen;
        public final int ply;

        Result(String fen, int ply) {
            this.fen = fen;
            this.ply = ply;
        }
    }

    public static final class InvalidPgnException extends Exception {
        InvalidPgnException(String message) {
            super(message);
        }
    }

    public static final class NoEndgameException extends Exception {
        NoEndgameException(String message) {
            super(message);
        }
    }

    public static Result run(String pgn) throws IOException, InterruptedException, InvalidPgnException, NoEndgameException {
        String python = firstNonBlank(
                System.getProperty("chess.endgamePython"),
                System.getenv("FEATURE_PYTHON"),
                "python3");
        String script = firstNonBlank(System.getProperty("chess.endgameCli"), null);
        ProcessBuilder builder;
        if (script != null) {
            builder = new ProcessBuilder(python, script);
        } else {
            builder = new ProcessBuilder(python, "-m", "chessapp.endgame");
            String dir = firstNonBlank(System.getenv("CHESSAPP_DIR"), System.getProperty("chess.chessappDir"));
            if (dir != null) {
                builder.directory(Path.of(dir).toFile());
            }
        }
        Path errFile = Files.createTempFile("ziga-endgame-cli-", ".err");
        builder.redirectError(errFile.toFile());
        Process process = null;
        try {
            process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(pgn.getBytes(StandardCharsets.UTF_8));
            }
            final byte[] stdout;
            try (InputStream in = process.getInputStream()) {
                stdout = in.readAllBytes();
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Endgame CLI timed out");
            }
            String stderr = Files.readString(errFile).trim();
            if (process.exitValue() == 2) {
                throw new InvalidPgnException(stderr.isEmpty() ? "Invalid PGN" : stderr);
            }
            if (process.exitValue() == 3) {
                throw new NoEndgameException(stderr.isEmpty() ? "No rook endgame in this game" : stderr);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Endgame CLI exited " + process.exitValue() + ": " + stderr);
            }
            return parse(new String(stdout, StandardCharsets.UTF_8));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(errFile);
        }
    }

    static Result parse(String stdout) throws IOException {
        final JsonObject obj;
        try {
            obj = new JsonParser().parse(stdout.trim()).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            throw new IOException("Endgame CLI returned non-JSON output");
        }
        if (!obj.has("fen") || !obj.get("fen").isJsonPrimitive()) {
            throw new IOException("Endgame CLI JSON missing fen");
        }
        String fen = obj.get("fen").getAsString().trim();
        if (fen.isEmpty()) {
            throw new IOException("Endgame CLI JSON missing fen");
        }
        int ply = 0;
        if (obj.has("ply") && obj.get("ply").isJsonPrimitive()) {
            ply = obj.get("ply").getAsInt();
        }
        return new Result(fen, ply);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        return null;
    }

    private static String firstNonBlank(String a, String b, String c) {
        String first = firstNonBlank(a, b);
        return first != null ? first : c;
    }
}
