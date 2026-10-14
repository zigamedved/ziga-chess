import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HTTP Basic auth for /position. Credentials come from the environment
 * (or {@link #configureAuth} in tests). No hardcoded passwords.
 */
public class AuthFilter {
    static String authUsername;
    static String authPassword;

    static void configureAuth(String username, String password) {
        authUsername = username;
        authPassword = password;
    }

    static void requireAuthConfigured() {
        if (isBlank(authUsername) || isBlank(authPassword)) {
            throw new IllegalStateException(
                    "AUTH_USERNAME and AUTH_PASSWORD must be set via the environment. "
                            + "See .env.example — hardcoded credentials have been removed.");
        }
    }

    /**
     * Validates Basic auth. Returns true when authorized.
     * On failure, sends 401 and returns false — callers must return immediately.
     */
    static boolean authorize(HttpExchange t) throws IOException {
        requireAuthConfigured();
        String authHeader = t.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || authHeader.isEmpty() || !authHeader.startsWith("Basic ")) {
            sendUnauthorized(t);
            return false;
        }

        String encodedCredentials = authHeader.substring("Basic ".length()).trim();
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedCredentials);
        } catch (IllegalArgumentException ex) {
            sendUnauthorized(t);
            return false;
        }

        String credentials = new String(decoded, StandardCharsets.UTF_8);
        int colon = credentials.indexOf(':');
        if (colon < 0) {
            sendUnauthorized(t);
            return false;
        }
        String username = credentials.substring(0, colon);
        String password = credentials.substring(colon + 1);
        if (!authUsername.equals(username) || !authPassword.equals(password)) {
            sendUnauthorized(t);
            return false;
        }
        return true;
    }

    static void sendUnauthorized(HttpExchange t) throws IOException {
        t.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"ziga-chess\"");
        t.sendResponseHeaders(401, -1);
        t.close();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
