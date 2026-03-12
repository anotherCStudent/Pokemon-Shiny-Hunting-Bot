package core.switchcontrol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SwitchClient {

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public SwitchClient(String host, int port) {
        this(host, port, 3000, 5000);
    }

    public SwitchClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.host = host.trim();
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public String connect() {
        return postJson("/connect", "{}");
    }

    public String disconnect() {
        return postJson("/disconnect", "{}");
    }

    public String status() {
        return get("/status");
    }

    public String pressButton(String button) {
        return pressButton(button, 0.08, 0.08);
    }

    public String pressButton(String button, double down, double up) {
        requireNonBlank(button, "button");
        String json = "{"
                + "\"button\":\"" + escapeJson(button) + "\","
                + "\"down\":" + down + ","
                + "\"up\":" + up
                + "}";
        return postJson("/button", json);
    }

    public String pressDpad(String direction) {
        return pressDpad(direction, 0.08, 0.08);
    }

    public String pressDpad(String direction, double down, double up) {
        requireNonBlank(direction, "direction");
        String json = "{"
                + "\"direction\":\"" + escapeJson(direction) + "\","
                + "\"down\":" + down + ","
                + "\"up\":" + up
                + "}";
        return postJson("/dpad", json);
    }

    public String holdDirection(String direction, double hold, double up) {
        requireNonBlank(direction, "direction");
        String json = "{"
                + "\"direction\":\"" + escapeJson(direction) + "\","
                + "\"hold\":" + hold + ","
                + "\"up\":" + up
                + "}";
        return postJson("/hold", json);
    }

    public String softReset() {
        return softReset(0.15, 0.10);
    }

    public String softReset(double down, double up) {
        String json = "{"
                + "\"down\":" + down + ","
                + "\"up\":" + up
                + "}";
        return postJson("/soft-reset", json);
    }

    public String macro(String macro) {
        return macro(macro, true);
    }

    public String macro(String macro, boolean block) {
        if (macro == null) {
            throw new IllegalArgumentException("macro cannot be null");
        }
        String json = "{"
                + "\"macro\":\"" + escapeJson(macro) + "\","
                + "\"block\":" + block
                + "}";
        return postJson("/macro", json);
    }

    public String clearMacros() {
        return postJson("/clear-macros", "{}");
    }

    private String get(String path) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(path, "GET");
            int code = conn.getResponseCode();
            String body = readResponseBody(conn, code);
            if (code >= 200 && code < 300) {
                return body;
            }
            throw new IllegalStateException("GET " + path + " failed with HTTP " + code + ": " + body);
        } catch (IOException e) {
            throw new IllegalStateException("GET " + path + " failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String postJson(String path, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(path, "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            String body = readResponseBody(conn, code);
            if (code >= 200 && code < 300) {
                return body;
            }
            throw new IllegalStateException("POST " + path + " failed with HTTP " + code + ": " + body);
        } catch (IOException e) {
            throw new IllegalStateException("POST " + path + " failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String path, String method) throws IOException {
        URL url = new URL("http://" + host + ":" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setUseCaches(false);
        return conn;
    }

    private String readResponseBody(HttpURLConnection conn, int code) throws IOException {
        InputStream stream = null;
        try {
            stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private void requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
    }

    private String escapeJson(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}