import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code python -m chessapp.cli} and reads its one-line JSON.
 * Tests point {@code chess.featureCli} at a stand-in script.
 */
public final class FeatureCli {
    private static final long TIMEOUT_SECONDS = 180;

    private FeatureCli() {
    }

    public static final class Result {
        public final String fen;
        public final String features;
        public final String pv1;
        public final String pv2;
        public final int engineDepth;
        public final int limitDepth;

        Result(String fen, String features, String pv1, String pv2) {
            this(fen, features, pv1, pv2, 12, 8);
        }

        Result(String fen, String features, String pv1, String pv2, int engineDepth, int limitDepth) {
            this.fen = fen;
            this.features = features;
            this.pv1 = pv1;
            this.pv2 = pv2;
            this.engineDepth = engineDepth;
            this.limitDepth = limitDepth;
        }
    }

    public static final class InvalidFenException extends Exception {
        InvalidFenException(String message) {
            super(message);
        }
    }

    public static Result run(String fen) throws IOException, InterruptedException, InvalidFenException {
        String python = firstNonBlank(
                System.getProperty("chess.featurePython"),
                System.getenv("FEATURE_PYTHON"),
                "python3");
        String script = firstNonBlank(System.getProperty("chess.featureCli"), null);
        ProcessBuilder builder;
        if (script != null) {
            builder = new ProcessBuilder(python, script, fen);
        } else {
            builder = new ProcessBuilder(python, "-m", "chessapp.cli", fen);
            String dir = firstNonBlank(System.getenv("CHESSAPP_DIR"), System.getProperty("chess.chessappDir"));
            if (dir != null) {
                builder.directory(Path.of(dir).toFile());
            }
        }
        Path errFile = Files.createTempFile("ziga-feature-cli-", ".err");
        builder.redirectError(errFile.toFile());
        Process process = null;
        try {
            process = builder.start();
            final byte[] stdout;
            try (InputStream in = process.getInputStream()) {
                stdout = in.readAllBytes();
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Feature CLI timed out");
            }
            String stderr = Files.readString(errFile);
            if (process.exitValue() == 2) {
                throw new InvalidFenException(stderr.isBlank() ? "Invalid FEN" : stderr.trim());
            }
            if (process.exitValue() != 0) {
                throw new IOException("Feature CLI exited " + process.exitValue() + ": " + stderr.trim());
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
        String trimmed = stdout.trim();
        final JsonObject obj;
        try {
            obj = new JsonParser().parse(trimmed).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            throw new IOException("Feature CLI returned non-JSON output");
        }
        if (!obj.has("features") || obj.get("features").isJsonNull()) {
            throw new IOException("Feature CLI JSON missing features");
        }
        return new Result(
                stringField(obj, "fen"),
                obj.get("features").getAsString(),
                stringField(obj, "pv1"),
                stringField(obj, "pv2"),
                intField(obj, "engineDepth", 12),
                intField(obj, "limitDepth", 8));
    }

    private static int intField(JsonObject obj, String name, int fallback) {
        if (!obj.has(name) || obj.get(name).isJsonNull() || !obj.get(name).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(name).getAsInt();
        } catch (NumberFormatException | ClassCastException ex) {
            return fallback;
        }
    }

    private static String stringField(JsonObject obj, String name) {
        if (!obj.has(name) || obj.get(name).isJsonNull()) {
            return "";
        }
        return obj.get(name).getAsString();
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
