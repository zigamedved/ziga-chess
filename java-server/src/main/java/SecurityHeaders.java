import com.sun.net.httpserver.HttpExchange;

/**
 * Headers on every response. The page is same-origin modules, CSS, fonts, and piece SVGs,
 * so the policy does not need inline script or a third-party font host.
 * {@code frame-ancestors} is the CSP directive that stops the page being framed.
 */
final class SecurityHeaders {
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "font-src 'self'",
            "img-src 'self'",
            "connect-src 'self'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "object-src 'none'");

    private SecurityHeaders() {
    }

    static void apply(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }
}
