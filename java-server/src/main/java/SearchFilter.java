import com.google.gson.JsonObject;

/**
 * Optional limits on {@code POST /analyse}. An empty filter leaves the BM25 query unchanged.
 */
final class SearchFilter {
    private static final String RESULT_1_0 = "1-0";
    private static final String RESULT_0_1 = "0-1";
    private static final String RESULT_DRAW = "1/2-1/2";

    final String result;
    final Integer minElo;
    final Integer maxElo;
    /** {@code rook} or {@code bishop}. Null leaves every family in the ranking. */
    final String family;

    private SearchFilter(String result, Integer minElo, Integer maxElo, String family) {
        this.result = result;
        this.minElo = minElo;
        this.maxElo = maxElo;
        this.family = family;
    }

    static SearchFilter none() {
        return new SearchFilter(null, null, null, null);
    }

    /** Same bounds, with a family limit added. */
    SearchFilter withFamily(String family) {
        return new SearchFilter(result, minElo, maxElo, family);
    }

    boolean isEmpty() {
        return result == null && minElo == null && maxElo == null && family == null;
    }

    static SearchFilter from(JsonObject obj) throws FenParser.BadRequestException {
        String result = readResult(obj);
        Integer minElo = readElo(obj, "minElo");
        Integer maxElo = readElo(obj, "maxElo");
        if (minElo != null && maxElo != null && minElo > maxElo) {
            throw new FenParser.BadRequestException("minElo is greater than maxElo");
        }
        return new SearchFilter(result, minElo, maxElo, readFamily(obj));
    }

    private static String readFamily(JsonObject obj) throws FenParser.BadRequestException {
        if (!obj.has("family") || obj.get("family").isJsonNull()) {
            return null;
        }
        if (!obj.get("family").isJsonPrimitive() || !obj.get("family").getAsJsonPrimitive().isString()) {
            throw new FenParser.BadRequestException("family must be rook or bishop");
        }
        String family = obj.get("family").getAsString().trim();
        if (family.isEmpty()) {
            return null;
        }
        if (!"rook".equals(family) && !"bishop".equals(family)) {
            throw new FenParser.BadRequestException("family must be rook or bishop");
        }
        return family;
    }

    private static String readResult(JsonObject obj) throws FenParser.BadRequestException {
        if (!obj.has("result") || obj.get("result").isJsonNull()) {
            return null;
        }
        if (!obj.get("result").isJsonPrimitive()) {
            throw new FenParser.BadRequestException("result must be 1-0, 0-1, or 1/2-1/2");
        }
        String result = obj.get("result").getAsString().trim();
        if (result.isEmpty()) {
            return null;
        }
        if (!RESULT_1_0.equals(result) && !RESULT_0_1.equals(result) && !RESULT_DRAW.equals(result)) {
            throw new FenParser.BadRequestException("result must be 1-0, 0-1, or 1/2-1/2");
        }
        return result;
    }

    private static Integer readElo(JsonObject obj, String name) throws FenParser.BadRequestException {
        if (!obj.has(name) || obj.get(name).isJsonNull()) {
            return null;
        }
        if (!obj.get(name).isJsonPrimitive()) {
            throw new FenParser.BadRequestException(name + " must be a non-negative integer");
        }
        String raw = obj.get(name).getAsString().trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (!raw.matches("[0-9]+")) {
            throw new FenParser.BadRequestException(name + " must be a non-negative integer");
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new FenParser.BadRequestException(name + " must be a non-negative integer");
        }
    }
}
