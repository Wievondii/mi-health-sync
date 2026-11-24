package com.mihealthsync.cloud;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpCookie;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/** 本人小米大陆云端的只读客户端。协议来源见第三方许可证说明。 */
public final class MiCloud {
    private static final String ACCOUNT = "https://account.xiaomi.com";
    private static final String HEALTH = "https://hlth.io.mi.com";
    private static final String RAW_PATH = "/app/v1/data/get_fitness_data_by_time";
    private static final String DAILY_PATH = "/app/v1/data/get_aggregated_fitness_data_by_time";
    private static final String SPORT_PATH = "/app/v1/data/get_sport_records_by_time";
    private static final String FILE_SIGN_PATH = "/service/gen_download_url";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private final String deviceCookie;
    private final HealthConnection healthConnection;

    public MiCloud() {
        this(url -> (HttpsURLConnection) url.openConnection());
    }

    MiCloud(HealthConnection healthConnection) {
        this.healthConnection = healthConnection;
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder id = new StringBuilder("deviceId=");
        for (byte b : bytes) id.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
        deviceCookie = id.toString();
    }

    interface HealthConnection {
        HttpsURLConnection open(URL url) throws IOException;
    }

    public static final class Challenge {
        final Uri poll;
        public final byte[] qrImage;

        Challenge(Uri poll, byte[] qrImage) {
            this.poll = poll;
            this.qrImage = qrImage;
        }
    }

    public static final class Result {
        public long earliest;
        public long latest;
        public int latestBpm;
        public int count;
        public int pages;
        public boolean more;
    }

    public static final class Session {
        private final String account;
        private final String cookie;
        private final String security;

        private Session(String account, String cookie, String security) {
            this.account = account;
            this.cookie = cookie;
            this.security = security;
        }

        public String accountScope() { return account; }

        public byte[] encode() throws Exception {
            return new JSONObject().put("account", account).put("cookie", cookie).put("security", security)
                    .toString().getBytes(StandardCharsets.UTF_8);
        }

        public static Session decode(byte[] bytes) throws Exception {
            JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return new Session(value.getString("account"), value.getString("cookie"), value.getString("security"));
        }
    }

    public enum Metric {
        HEART_RATE("heart_rate", false), SPO2("spo2", false), SINGLE_SPO2("single_spo2", false),
        RESTING_HEART_RATE("resting_heart_rate", false), STEPS("steps", true), SLEEP("sleep", false),
        SLEEP_DAILY("sleep", true), CALORIES_DAILY("calories", true), WEIGHT("weight", false);

        private final String key;
        private final boolean daily;

        Metric(String key, boolean daily) {
            this.key = key;
            this.daily = daily;
        }
    }

    public static final class Page {
        public final JSONArray rows;
        public final String next;
        public final boolean hasMore;

        private Page(JSONArray rows, String next, boolean hasMore) {
            this.rows = rows;
            this.next = next;
            this.hasMore = hasMore;
        }
    }

    public Challenge start() throws Exception {
        Uri endpoint = Uri.parse(ACCOUNT + "/longPolling/loginUrl?sid=miothealth&_json=true");
        Response response = get(endpoint, deviceCookie, false, 20000);
        JSONObject challenge = accountJson(response, "申请扫码");
        Uri poll = trusted(challenge.optString("lp"), "(?:[a-z0-9-]+\\.)?lp\\.account\\.xiaomi\\.com", "/lp/s");
        Uri qr = trusted(challenge.optString("qr"), "account\\.xiaomi\\.com", "/pass/qr/login");
        Response image = get(qr, deviceCookie, false, 10000);
        if (image.status != 200) throw new ProbeFailure("无法加载小米二维码（HTTP " + image.status + "）。");
        return new Challenge(poll, image.body);
    }

    public Result readAfterApproval(Challenge challenge) throws Exception {
        return readHeart(complete(challenge));
    }

    public Session complete(Challenge challenge) throws Exception {
        Response approval = get(challenge.poll, deviceCookie, false, 130000);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JSONObject login = accountJson(approval, "小米账号确认");
        if (login.optInt("securityStatus", 0) != 0) {
            throw new ProbeFailure("小米要求额外安全验证，本次读取已停止。");
        }
        JSONObject extension = approval.pragma == null ? new JSONObject() : new JSONObject(approval.pragma);
        String userId = cookieValue(login, approval, "userId");
        String cUserId = cookieValue(login, approval, "cUserId");
        String security = field(login, extension, "ssecurity");
        String nonce = field(login, extension, "nonce");
        if (security.isEmpty() || !nonce.matches("[0-9]+")) {
            throw new ProbeFailure("小米账号响应缺少签发健康会话所需字段。");
        }
        Uri ticket = trusted(login.optString("location"), "sts-hlth\\.io\\.mi\\.com", "/healthapp/sts");
        Uri exchange = ticket.buildUpon()
                .appendQueryParameter("clientSign", sha1("nonce=" + nonce + "&" + security))
                .appendQueryParameter("_userIdNeedEncrypt", "true").build();
        Response session = get(exchange, null, false, 20000);
        if (session.status != 200 && session.status != 302) {
            throw new ProbeFailure("健康会话签发失败（HTTP " + session.status + "）。");
        }
        String token = session.cookies.get("miothealth_serviceToken");
        if (token == null) token = session.cookies.get("serviceToken");
        String cookies = "cUserId=" + checkedCookie(cUserId) + "; serviceToken=" + checkedCookie(token);
        if (!userId.isEmpty()) cookies += "; userId=" + checkedCookie(userId);
        String scope = b64(MessageDigest.getInstance("SHA-256")
                .digest((userId.isEmpty() ? cUserId : userId).getBytes(StandardCharsets.UTF_8)));
        return new Session(scope, cookies, security);
    }

    public Page fetch(Session session, Metric metric, long start, long end, String cursor) throws Exception {
        JSONObject range = range(start, end, cursor).put("key", metric.key);
        if (metric.daily) range.put("tag", "daily_report").put("limit", 50);
        return page(session, range, metric.daily ? DAILY_PATH : RAW_PATH, "data_list");
    }

    public Page fetchSports(Session session, long start, long end, String cursor) throws Exception {
        return page(session, range(start, end, cursor).put("limit", 50), SPORT_PATH, "sport_records");
    }

    private JSONObject range(long start, long end, String cursor) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (end <= start || end - start > 33L * 86400) throw new IllegalArgumentException("小米查询范围无效。");
        JSONObject range = new JSONObject().put("start_time", start)
                .put("end_time", end).put("reverse", true);
        if (cursor != null && !cursor.isEmpty()) range.put("next_key", cursor);
        return range;
    }

    private Page page(Session session, JSONObject range, String path, String rowsKey) throws Exception {
        JSONObject reply = healthJson(range, session, path, path);
        JSONObject body = reply.optJSONObject("result");
        if (body == null) body = reply;
        JSONArray rows = body.optJSONArray(rowsKey);
        if (rows == null && !body.has("has_more")) throw new ProbeFailure("云端响应缺少记录字段。");
        return new Page(rows == null ? new JSONArray() : rows, body.optString("next_key", ""), body.optBoolean("has_more"));
    }

    public enum SportFile {
        SAMPLES(0), ROUTE(2);
        private final int code;
        SportFile(int code) { this.code = code; }
    }

    public byte[] fetchSportFile(Session session, String sourceId, long timestamp, int zone, int format, SportFile file)
            throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (sourceId.trim().isEmpty() || timestamp <= 0 || timestamp > 0xffffffffL ||
                zone < -72 || zone > 72 || (format != 22 && format != 23)) {
            return null;
        }
        String suffix = sportFileSuffix(sourceId, timestamp, zone, format, file);
        JSONObject item = new JSONObject().put("timestamp", timestamp).put("suffix", suffix);
        JSONObject request = new JSONObject().put("did", sourceId).put("items", new JSONArray().put(item));
        JSONObject reply = healthJson(request, session, "/healthapp" + FILE_SIGN_PATH, FILE_SIGN_PATH).optJSONObject("result");
        if (reply == null) throw new ProbeFailure("运动明细响应缺少文件列表。");
        JSONObject entry = reply.optJSONObject(suffix + "_" + timestamp);
        if (entry == null || entry.optString("obj_key").isEmpty()) return null;
        String address = entry.optString("url");
        if (!"GET".equals(entry.optString("method")) || address.isEmpty() || !address.matches("[!-\\[\\]-~]+")) {
            throw new ProbeFailure("小米运动文件地址无法验证。");
        }
        Uri endpoint = Uri.parse(address);
        if (!"https".equals(endpoint.getScheme()) || endpoint.getUserInfo() != null || endpoint.getPort() != -1 ||
                endpoint.getHost() == null || !endpoint.getHost().matches("(?:[a-z0-9-]{1,63}\\.)+fds\\.api\\.xiaomi\\.com")) {
            throw new ProbeFailure("小米运动文件地址无法验证。");
        }
        Response download = get(endpoint, null, false, 20000);
        if (download.status == 404) return null;
        if (download.status != 200) throw new IOException("小米运动文件暂不可读取。");
        return decryptSportFile(download.body, entry.getString("obj_key"));
    }

    static String sportFileSuffix(String sourceId, long timestamp, int zone, int format, SportFile file) throws Exception {
        byte[] identifier = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) timestamp).put((byte) zone).put((byte) (128 + (format << 2) + file.code)).array();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(sourceId.getBytes(StandardCharsets.UTF_8));
        return encoder.encodeToString(identifier) + "_" + encoder.encodeToString(hash);
    }

    private static byte[] decryptSportFile(byte[] body, String encodedKey) throws Exception {
        try {
            String encoded = new String(body, StandardCharsets.US_ASCII).trim();
            if (encoded.startsWith("\"") && encoded.endsWith("\"")) encoded = encoded.substring(1, encoded.length() - 1);
            byte[] key = Base64.getUrlDecoder().decode(encodedKey);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            try {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec("1234567887654321".getBytes(StandardCharsets.US_ASCII)));
                return cipher.doFinal(Base64.getUrlDecoder().decode(encoded));
            } finally {
                java.util.Arrays.fill(key, (byte) 0);
            }
        } catch (Exception error) {
            throw new ProbeFailure("小米运动文件无法解析。");
        }
    }

    private Result readHeart(Session session) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long end = Instant.now().getEpochSecond();
        long start = end - 86400;
        Result result = new Result();
        String cursor = "";
        for (int page = 0; page < 6; page++) {
            if (page > 0) Thread.sleep(1000L);
            Page response = fetch(session, Metric.HEART_RATE, start - 86400, end, cursor);
            addSamples(response.rows, result, start, end);
            result.pages++;
            result.more = response.hasMore;
            if (!result.more) break;
            String next = response.next;
            if (next.isEmpty() || next.equals(cursor)) break;
            cursor = next;
        }
        return result;
    }

    private static void addSamples(JSONArray rows, Result result, long start, long end) {
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) continue;
            try {
                JSONObject value = new JSONObject(row.optString("value"));
                long time = value.optLong("time", -1);
                int bpm = value.optInt("bpm", -1);
                if (time < start || time > end || bpm < 1 || bpm > 300) continue;
                if (result.count == 0 || time < result.earliest) result.earliest = time;
                if (time > result.latest) {
                    result.latest = time;
                    result.latestBpm = bpm;
                }
                result.count++;
            } catch (org.json.JSONException ignored) {
                // 略过无法解释的记录，诊断不输出原文。
            }
        }
    }

    private JSONObject healthJson(JSONObject range, Session session, String path, String signPath) throws Exception {
        byte[] nonceBytes = new byte[12];
        new SecureRandom().nextBytes(nonceBytes);
        ByteBuffer.wrap(nonceBytes).putInt(8, (int) (System.currentTimeMillis() / 60000));
        byte[] key = healthKey(session.security, nonceBytes);
        StringJoiner query = new StringJoiner("&");
        for (Map.Entry<String, String> parameter : signedParams(range.toString(), nonceBytes, key, signPath).entrySet()) {
            query.add(parameter.getKey() + "=" + URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8.name()));
        }
        Response response = get(healthConnection.open(new URL(HEALTH + path + "?" + query)), session.cookie, true, 20000);
        if (response.status != 200) throw new ProbeFailure("云端请求失败（HTTP " + response.status + "）。",
                response.status, response.retryAfter);
        JSONObject data = new JSONObject(decrypt(response.text(), key));
        int code = data.optInt("code", Integer.MIN_VALUE);
        if (code != 0) throw new ProbeFailure("云端拒绝读取（业务码 " + code + "）。");
        return data;
    }

    static byte[] healthKey(String security, byte[] nonceBytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Base64.getDecoder().decode(security));
        return digest.digest(nonceBytes);
    }

    static TreeMap<String, String> signedParams(String json, byte[] nonceBytes, byte[] key, String path) throws Exception {
        String signedNonce = b64(key);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("data", json);
        params.put("rc4_hash__", signature(params, signedNonce, path));
        Rc4 cipher = new Rc4(key);
        params.replaceAll((name, value) -> b64(cipher.apply(value.getBytes(StandardCharsets.UTF_8))));
        params.put("signature", signature(params, signedNonce, path));
        params.put("_nonce", b64(nonceBytes));
        return params;
    }

    static String decrypt(String encoded, byte[] key) {
        byte[] decrypted = new Rc4(key).apply(Base64.getDecoder().decode(encoded.trim()));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private static String signature(Map<String, String> params, String signedNonce, String path) throws Exception {
        StringBuilder input = new StringBuilder("GET&").append(path);
        params.forEach((name, value) -> input.append('&').append(name).append('=').append(value));
        return sha1(input.append('&').append(signedNonce).toString());
    }

    private static String sha1(String value) throws Exception {
        return b64(MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }

    private static final class Rc4 {
        private final int[] state = new int[256];
        private int i;
        private int j;

        Rc4(byte[] key) {
            for (int n = 0; n < state.length; n++) state[n] = n;
            int mix = 0;
            for (int n = 0; n < state.length; n++) {
                mix = (mix + state[n] + (key[n % key.length] & 255)) & 255;
                swap(n, mix);
            }
            apply(new byte[1024]);
        }

        byte[] apply(byte[] input) {
            byte[] output = new byte[input.length];
            for (int n = 0; n < input.length; n++) {
                i = (i + 1) & 255;
                j = (j + state[i]) & 255;
                swap(i, j);
                output[n] = (byte) (input[n] ^ state[(state[i] + state[j]) & 255]);
            }
            return output;
        }

        private void swap(int left, int right) {
            int value = state[left];
            state[left] = state[right];
            state[right] = value;
        }
    }

    private static JSONObject accountJson(Response response, String phase) throws Exception {
        if (response.status != 200) throw new ProbeFailure(phase + "失败（HTTP " + response.status + "）。");
        String text = response.text().trim();
        if (text.startsWith("&&&START&&&")) text = text.substring("&&&START&&&".length());
        JSONObject json = new JSONObject(text);
        int code = json.optInt("code", Integer.MIN_VALUE);
        if (code != 0) throw new ProbeFailure(phase + "未通过（业务码 " + code + "）。");
        return json;
    }

    private static Uri trusted(String raw, String hostPattern, String path) throws ProbeFailure {
        Uri uri = Uri.parse(raw);
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getHost() == null || !uri.getHost().matches(hostPattern) || !path.equals(uri.getPath())) {
            throw new ProbeFailure("小米返回的地址与已核实的服务不一致，本次读取已停止。");
        }
        return uri;
    }

    private static String field(JSONObject body, JSONObject fallback, String name) {
        String result = body.optString(name, "");
        return result.isEmpty() ? fallback.optString(name, "") : result;
    }

    private static String cookieValue(JSONObject body, Response response, String name) {
        String value = body.optString(name, "");
        return value.isEmpty() ? response.cookies.getOrDefault(name, "") : value;
    }

    private static String checkedCookie(String value) throws ProbeFailure {
        if (value == null || value.isEmpty() || !value.matches("[\\x21-\\x7E&&[^;\"\\\\,]]+")) {
            throw new ProbeFailure("小米未返回可用的健康会话。");
        }
        return value;
    }

    private static Response get(Uri url, String cookie, boolean health, int timeout) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url.toString()).openConnection();
        return get(connection, cookie, health, timeout);
    }

    private static Response get(HttpsURLConnection connection, String cookie, boolean health, int timeout) throws IOException {
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(timeout);
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        if (health) connection.setRequestProperty("region_tag", "cn");
        try {
            int status = connection.getResponseCode();
            Map<String, String> cookies = new HashMap<>();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
                for (String value : header.getValue()) {
                    try {
                        for (HttpCookie parsed : HttpCookie.parse(value)) cookies.put(parsed.getName(), parsed.getValue());
                    } catch (IllegalArgumentException ignored) {
                        // 拒绝无法解析的 Cookie。
                    }
                }
            }
            byte[] bytes = new byte[0];
            if (status == 200) {
                try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        if (body.size() + read > MAX_BODY_BYTES) throw new IOException("响应过大");
                        body.write(buffer, 0, read);
                    }
                    bytes = body.toByteArray();
                }
            }
            return new Response(status, bytes, cookies, connection.getHeaderField("Extension-Pragma"),
                    connection.getHeaderField("Retry-After"));
        } finally {
            connection.disconnect();
        }
    }

    private static final class Response {
        final int status;
        final byte[] body;
        final Map<String, String> cookies;
        final String pragma;
        final String retryAfter;

        Response(int status, byte[] body, Map<String, String> cookies, String pragma, String retryAfter) {
            this.status = status;
            this.body = body;
            this.cookies = cookies;
            this.pragma = pragma;
            this.retryAfter = retryAfter;
        }

        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    public static final class ProbeFailure extends IOException {
        public final int status;
        public final String retryAfter;

        ProbeFailure(String message) { this(message, 0, null); }

        public ProbeFailure(String message, int status, String retryAfter) {
            super(message);
            this.status = status;
            this.retryAfter = retryAfter;
        }
    }
}
