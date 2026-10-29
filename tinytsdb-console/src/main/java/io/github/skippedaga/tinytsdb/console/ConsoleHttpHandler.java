package io.github.skippedaga.tinytsdb.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Serves the console on the JDK's {@link HttpServer}: the page and its two assets from the classpath
 * ({@code /tinytsdb/console/}), and {@code api/*} from {@link ConsoleApi}, under one mount path.
 *
 * <pre>
 * HttpServer server = HttpServer.create(new InetSocketAddress(8090), 0);
 * server.createContext("/tinytsdb", new ConsoleHttpHandler(api, "/tinytsdb", exchange -> true));
 * server.start();
 * </pre>
 *
 * <p>Every answer carries a strict Content-Security-Policy (scripts, styles and requests only from the same origin,
 * no framing), {@code X-Content-Type-Options: nosniff} and {@code Referrer-Policy: no-referrer}. The page is
 * read-only; put it behind your own authentication — {@code allow} decides per request, false answers 403.</p>
 */
public final class ConsoleHttpHandler implements HttpHandler {

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private static final Map<String, String> ASSETS = Map.of(
            "console.css", "text/css; charset=utf-8",
            "console.js", "text/javascript; charset=utf-8");

    private final ConsoleApi api;
    private final String mount;
    private final Predicate<HttpExchange> allow;
    private final byte[] index;
    private final Map<String, byte[]> assets = new HashMap<>();

    /**
     * @param mount the context path the handler is registered under, e.g. {@code /tinytsdb}
     * @param allow decides whether a request may see the console
     */
    public ConsoleHttpHandler(ConsoleApi api, String mount, Predicate<HttpExchange> allow) {
        this.api = api;
        this.mount = mount.endsWith("/") ? mount.substring(0, mount.length() - 1) : mount;
        this.allow = allow;
        this.index = resource("index.html");
        ASSETS.keySet().forEach(name -> assets.put(name, resource(name)));
    }

    /** Starts a {@link HttpServer} with only the console on it: for a sidecar port or local use. */
    public static HttpServer serve(ConsoleApi api, InetSocketAddress address, String mount,
                                   Predicate<HttpExchange> allow) throws IOException {
        HttpServer server = HttpServer.create(address, 0);
        server.createContext(mount, new ConsoleHttpHandler(api, mount, allow));
        server.start();
        return server;
    }

    private static byte[] resource(String name) {
        try (InputStream in = ConsoleHttpHandler.class.getResourceAsStream("/tinytsdb/console/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing console resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            if (!allow.test(exchange)) {
                send(exchange, 403, "text/plain; charset=utf-8", "Forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (!path.startsWith(mount)) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String rest = path.substring(mount.length());
            if (rest.isEmpty()) {
                exchange.getResponseHeaders().set("Location", mount + "/");
                send(exchange, 308, "text/plain; charset=utf-8", new byte[0]);
                return;
            }
            if (rest.equals("/") || rest.equals("/index.html")) {
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                send(exchange, 200, "text/html; charset=utf-8", index);
                return;
            }
            if (rest.startsWith("/assets/")) {
                String name = rest.substring("/assets/".length());
                byte[] body = assets.get(name);
                if (body == null) {
                    send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                send(exchange, 200, ASSETS.get(name), body);
                return;
            }
            if (rest.startsWith("/api/")) {
                ConsoleApi.Response r = api.handle(exchange.getRequestMethod(), rest.substring("/api/".length()),
                        query(exchange.getRequestURI().getRawQuery()));
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                send(exchange, r.status(), r.contentType(), r.body().getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /** Decoded query parameters; the last value of a repeated name wins. */
    static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }
}
