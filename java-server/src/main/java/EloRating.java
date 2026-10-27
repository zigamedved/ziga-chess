/**
 * Elo used for a range filter. A game may store a rating for one side only.
 * The indexed value is the average of the ratings that parse as non-negative integers.
 */
final class EloRating {
    private EloRating() {
    }

    static Integer average(String white, String black) {
        Integer left = parse(white);
        Integer right = parse(black);
        if (left == null && right == null) {
            return null;
        }
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.round((left + right) / 2.0f);
    }

    static Integer parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        final int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
        return value >= 0 ? value : null;
    }
}
