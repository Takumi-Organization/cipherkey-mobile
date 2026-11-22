package dev.cipherkey.core.sync;

import com.google.gson.*;
import dev.cipherkey.core.sync.Protocol.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Cliente HTTPS del servidor de sincronización (HttpURLConnection para funcionar igual en JVM y Android). */
public final class ServerClient implements Transport {
    public static class ServerException extends IOException {
        public final int status;
        public ServerException(int status, String msg) { super("HTTP " + status + ": " + msg); this.status = status; }
    }

    public record DeviceInfo(String deviceId, String name, long createdAt, long lastSeen, boolean revoked) {}
    public record Enrollment(String token, long expiresAt) {}

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String base;
    private final String deviceId;
    private final DeviceKey key;

    public ServerClient(String baseUrl, String deviceId, DeviceKey key) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.deviceId = deviceId;
        this.key = key;
    }

    // ---- alta (sin firma) ----
    public static String createVault(String baseUrl, String adminToken, String vaultId, DeviceKey key, String deviceName) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("vaultId", vaultId); b.addProperty("devicePublicKey", key.publicBase64()); b.addProperty("deviceName", deviceName);
        Map<String, String> h = new HashMap<>();
        if (adminToken != null && !adminToken.isEmpty()) h.put("X-Admin-Token", adminToken);
        return JsonParser.parseString(raw("POST", trim(baseUrl) + "/v1/vaults", b.toString().getBytes(StandardCharsets.UTF_8), h))
                .getAsJsonObject().get("deviceId").getAsString();
    }

    public static String enroll(String baseUrl, String vaultId, String token, DeviceKey key, String deviceName) throws IOException {
        JsonObject b = new JsonObject();
        b.addProperty("vaultId", vaultId); b.addProperty("token", token);
        b.addProperty("devicePublicKey", key.publicBase64()); b.addProperty("deviceName", deviceName);
        return JsonParser.parseString(raw("POST", trim(baseUrl) + "/v1/enroll", b.toString().getBytes(StandardCharsets.UTF_8), Map.of()))
                .getAsJsonObject().get("deviceId").getAsString();
    }

    // ---- con firma ----
    public Enrollment newEnrollment() throws IOException {
        JsonObject o = JsonParser.parseString(signed("POST", "/v1/enrollments", new byte[0])).getAsJsonObject();
        return new Enrollment(o.get("token").getAsString(), o.get("expiresAt").getAsLong());
    }

    public List<DeviceInfo> devices() throws IOException {
        List<DeviceInfo> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(signed("GET", "/v1/devices", new byte[0])).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            out.add(new DeviceInfo(o.get("deviceId").getAsString(), o.get("name").getAsString(), o.get("createdAt").getAsLong(),
                    o.get("lastSeen").getAsLong(), o.get("revoked").getAsBoolean()));
        }
        return out;
    }

    public void revoke(String targetDeviceId) throws IOException {
        signed("DELETE", "/v1/devices/" + targetDeviceId, new byte[0]);
    }

    @Override
    public PullResponse pull(long since, int limit) throws IOException {
        JsonObject o = JsonParser.parseString(signed("GET", "/v1/changes?since=" + since + "&limit=" + limit, new byte[0])).getAsJsonObject();
        List<Change> list = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("changes")) list.add(change(e.getAsJsonObject()));
        return new PullResponse(list, o.get("latestSeq").getAsLong(), o.get("more").getAsBoolean());
    }

    @Override
    public List<PushResult> push(List<PushOp> ops) throws IOException {
        JsonArray arr = new JsonArray();
        for (PushOp op : ops) {
            JsonObject o = new JsonObject();
            o.addProperty("opId", op.opId()); o.addProperty("uuid", op.uuid()); o.addProperty("baseVer", op.baseVer());
            o.addProperty("deleted", op.deleted()); o.addProperty("blob", Base64.getEncoder().encodeToString(op.blob()));
            arr.add(o);
        }
        JsonObject body = new JsonObject();
        body.add("ops", arr);
        JsonObject resp = JsonParser.parseString(signed("POST", "/v1/changes", body.toString().getBytes(StandardCharsets.UTF_8))).getAsJsonObject();
        List<PushResult> out = new ArrayList<>();
        for (JsonElement e : resp.getAsJsonArray("results")) {
            JsonObject o = e.getAsJsonObject();
            out.add(new PushResult(o.get("opId").getAsString(), o.get("status").getAsString(),
                    o.has("ver") ? o.get("ver").getAsLong() : 0, o.has("seq") ? o.get("seq").getAsLong() : 0,
                    o.has("current") && !o.get("current").isJsonNull() ? change(o.getAsJsonObject("current")) : null));
        }
        return out;
    }

    private static Change change(JsonObject o) {
        return new Change(o.get("uuid").getAsString(), o.get("ver").getAsLong(), o.get("seq").getAsLong(),
                o.get("deviceId").getAsString(), o.get("deleted").getAsBoolean(), Base64.getDecoder().decode(o.get("blob").getAsString()));
    }

    private String signed(String method, String pathAndQuery, byte[] body) throws IOException {
        long ts = System.currentTimeMillis();
        byte[] n = new byte[16];
        RANDOM.nextBytes(n);
        String nonce = B64URL.encodeToString(n);
        String sig = B64URL.encodeToString(key.sign(DeviceKey.canonical(method, pathAndQuery, ts, nonce, body)));
        Map<String, String> h = Map.of("Authorization", "CK1 device=" + deviceId + ",ts=" + ts + ",nonce=" + nonce + ",sig=" + sig);
        return raw(method, base + pathAndQuery, body, h);
    }

    private static String trim(String u) { return u.endsWith("/") ? u.substring(0, u.length() - 1) : u; }

    private static String raw(String method, String url, byte[] body, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestMethod(method);
        headers.forEach(c::setRequestProperty);
        if (body.length > 0) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : readAll(in);
        if (code >= 400) throw new ServerException(code, text);
        return text;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
