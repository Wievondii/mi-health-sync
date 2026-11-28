package com.mihealthsync.cloud;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class MiCloudRenewalTest {
    private static final String USER_ID = "123456789";
    private static final String SECURITY = "dGVzdC1rZXktb25seQ==";
    private static final String POLL_PATH = "/lp/s";
    private static final String STS_PATH = "/healthapp/sts";
    private static final String RENEW_PATH = "/pass/serviceLogin";
    private final MockWebServer server = new MockWebServer();
    private MiCloud cloud;
    private HandshakeCertificates clientCertificates;

    @Before
    public void startServer() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
                .addSubjectAlternativeName("::1").build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        clientCertificates = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.start();
        cloud = newClient();
    }

    @After
    public void stopServer() throws Exception {
        server.shutdown();
    }

    private MiCloud newClient() {
        HttpUrl localOrigin = server.url("/");
        MiCloud.ConnectionFactory local = url -> {
            assertEquals("https", url.getProtocol());
            switch (url.getHost()) {
                case "account.xiaomi.com", "ak.lp.account.xiaomi.com", "sts-hlth.io.mi.com", "hlth.io.mi.com" -> {}
                default -> fail("请求发到了未指定的测试主机");
            }
            HttpUrl target = localOrigin.resolve(url.getFile());
            assertNotNull(target);
            HttpsURLConnection connection = (HttpsURLConnection) target.url().openConnection();
            connection.setSSLSocketFactory(clientCertificates.sslSocketFactory());
            return connection;
        };
        return new MiCloud(local, local);
    }

    @Test
    public void renewsSavedSessionWithTheOriginalDeviceAndRotatedCredential() throws Exception {
        MiCloud.Session scanned = scan(true);
        MiCloud.Session saved = MiCloud.Session.decode(scanned.encode());
        assertTrue(saved.canRenew());
        String deviceCookie = takeRequest("/longPolling/loginUrl").getHeader("Cookie");
        assertTrue(deviceCookie.matches("deviceId=[0-9A-F]{16}"));
        assertEquals(deviceCookie, takeRequest("/pass/qr/login").getHeader("Cookie"));
        assertEquals(deviceCookie, takeRequest(POLL_PATH).getHeader("Cookie"));
        assertNull(takeRequest(STS_PATH).getHeader("Cookie"));

        enqueueAccount(accountLogin().put("userId", USER_ID), "passToken=rotated-fixture");
        enqueueServiceToken("second-token");
        MiCloud.Session renewed = newClient().renew(saved);

        RecordedRequest renew = takeRequest(RENEW_PATH);
        assertEquals("miothealth", renew.getRequestUrl().queryParameter("sid"));
        assertEquals("true", renew.getRequestUrl().queryParameter("_json"));
        assertEquals("userId=" + USER_ID + "; passToken=first-fixture; " + deviceCookie, renew.getHeader("Cookie"));
        RecordedRequest sts = takeRequest(STS_PATH);
        assertEquals("sts-fixture", sts.getRequestUrl().queryParameter("ticket"));
        assertNotNull(sts.getRequestUrl().queryParameter("clientSign"));
        assertEquals("true", sts.getRequestUrl().queryParameter("_userIdNeedEncrypt"));
        assertNull(sts.getHeader("Cookie"));
        assertEquals(saved.accountScope(), renewed.accountScope());

        enqueueAccount(accountLogin(), null);
        enqueueServiceToken("third-token");
        MiCloud.Session latest = newClient().renew(MiCloud.Session.decode(renewed.encode()));
        assertEquals(renewed.accountScope(), latest.accountScope());
        assertEquals("userId=" + USER_ID + "; passToken=rotated-fixture; " + deviceCookie,
                takeRequest(RENEW_PATH).getHeader("Cookie"));
        assertNull(takeRequest(STS_PATH).getHeader("Cookie"));

        server.enqueue(new MockResponse().setResponseCode(401));
        MiCloud.ProbeFailure readFailure = assertThrows(MiCloud.ProbeFailure.class,
                () -> cloud.fetch(latest, MiCloud.Metric.HEART_RATE, 100, 200, null));
        assertEquals(401, readFailure.status);
        assertEquals("cUserId=c-fixture; serviceToken=third-token; userId=" + USER_ID,
                takeRequest("/app/v1/data/get_fitness_data_by_time").getHeader("Cookie"));
    }

    @Test
    public void keepsLegacyAndOptedOutSessionsUsableWithoutRenewal() throws Exception {
        JSONObject legacy = new JSONObject().put("account", "old-fixture")
                .put("cookie", "serviceToken=legacy-fixture").put("security", SECURITY);
        MiCloud.Session old = MiCloud.Session.decode(legacy.toString().getBytes(StandardCharsets.UTF_8));
        assertFalse(old.canRenew());
        assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(old));
        assertEquals(0, server.getRequestCount());

        MiCloud.Session optedOut = scan(false);
        assertFalse(optedOut.canRenew());
        assertFalse(new JSONObject(new String(optedOut.encode(), StandardCharsets.UTF_8)).has("renewal"));
        assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(optedOut));
        assertEquals(4, server.getRequestCount());

        MiCloud.Session previous = scan(true);
        MiCloud.Session stripped = MiCloud.Session.decode(previous.withoutRenewal().encode());
        assertFalse(stripped.canRenew());
        assertEquals(previous.accountScope(), stripped.accountScope());
        assertFalse(new String(stripped.encode(), StandardCharsets.UTF_8).contains("first-fixture"));
        server.enqueue(new MockResponse().setResponseCode(401));
        assertThrows(MiCloud.ProbeFailure.class, () -> cloud.fetch(stripped, MiCloud.Metric.HEART_RATE, 100, 200, null));
        for (int i = 0; i < 8; i++) assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        assertTrue(takeRequest("/app/v1/data/get_fitness_data_by_time").getHeader("Cookie")
                .contains("serviceToken=first-token"));
    }

    @Test
    public void rejectsSecurityChallengesAndChangedAccountBeforeStsExchange() throws Exception {
        MiCloud.Session session = scan(true);
        enqueueAccount(accountLogin().put("securityStatus", 1)
                .put("notificationUrl", "https://account.xiaomi.com/private-fixture"), null);
        MiCloud.RenewalRejected blocked = assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(session));
        assertTrue(blocked.getMessage().contains("安全验证"));
        assertFalse(blocked.getMessage().contains("private-fixture"));
        assertEquals(5, server.getRequestCount());

        enqueueAccount(accountLogin().put("userId", "987654321"), null);
        assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(session));
        assertEquals(6, server.getRequestCount());

        enqueueAccount(accountLogin().put("userId", USER_ID), "userId=987654321");
        assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(session));
        assertEquals(7, server.getRequestCount());
    }

    @Test
    public void blocksUnexpectedAccountRedirectsAndUntrustedStsUrls() throws Exception {
        MiCloud.Session session = scan(true);
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")));
        MiCloud.RenewalRejected refused = assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(session));
        assertTrue(refused.getMessage().contains("302"));
        assertEquals(5, server.getRequestCount());

        enqueueAccount(accountLogin().put("location", "https://sts-hlth.io.mi.com.invalid/healthapp/sts?ticket=private-fixture"), null);
        MiCloud.RenewalRejected untrusted = assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(session));
        assertFalse(untrusted.getMessage().contains("private-fixture"));
        assertEquals(6, server.getRequestCount());
    }

    @Test
    public void preservesTransientFailuresWithoutTreatingThemAsExpiredAuthorization() throws Exception {
        MiCloud.Session session = scan(true);
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "37"));
        MiCloud.ProbeFailure limited = assertThrows(MiCloud.ProbeFailure.class, () -> cloud.renew(session));
        assertEquals(429, limited.status);
        assertEquals("37", limited.retryAfter);
        enqueueAccount(accountLogin(), null);
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "12"));
        MiCloud.ProbeFailure unavailable = assertThrows(MiCloud.ProbeFailure.class, () -> cloud.renew(session));
        assertEquals(503, unavailable.status);
        assertEquals("12", unavailable.retryAfter);

        MiCloud disconnected = new MiCloud(url -> { throw new AssertionError(); },
                url -> { throw new IOException("https://account.xiaomi.com/private-fixture"); });
        IOException network = assertThrows(IOException.class, () -> disconnected.renew(session));
        assertFalse(network instanceof MiCloud.ProbeFailure);
        assertFalse(network.getMessage().contains("private-fixture"));
        assertNull(network.getCause());
    }

    @Test
    public void rejectsUnchangedHealthCredentialsAndRedactsMalformedResponse() throws Exception {
        MiCloud.Session session = scan(true);
        enqueueAccount(accountLogin(), null);
        enqueueServiceToken("first-token");
        assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(session));

        server.enqueue(new MockResponse().setBody("private-malformed-fixture"));
        MiCloud.RenewalRejected malformed = assertThrows(MiCloud.RenewalRejected.class, () -> cloud.renew(session));
        assertFalse(malformed.getMessage().contains("fixture"));
        assertNull(malformed.getCause());
    }

    private MiCloud.Session scan(boolean retain) throws Exception {
        enqueueAccount(new JSONObject().put("code", 0)
                .put("lp", "https://ak.lp.account.xiaomi.com/lp/s?ticket=poll-fixture")
                .put("qr", "https://account.xiaomi.com/pass/qr/login?ticket=qr-fixture"), null);
        server.enqueue(new MockResponse().setBody("qr-fixture"));
        enqueueAccount(accountLogin().put("userId", USER_ID).put("cUserId", "c-fixture").put("securityStatus", 0)
                .put("notificationUrl", "https://account.xiaomi.com/legacy-fixture")
                .put("passToken", "first-fixture"), null);
        enqueueServiceToken("first-token");
        return cloud.complete(cloud.start(), retain);
    }

    private static JSONObject accountLogin() throws Exception {
        return new JSONObject().put("code", 0).put("ssecurity", SECURITY).put("nonce", "1144")
                .put("location", "https://sts-hlth.io.mi.com/healthapp/sts?ticket=sts-fixture");
    }

    private void enqueueAccount(JSONObject body, String cookie) {
        MockResponse response = new MockResponse().setBody("&&&START&&&" + body);
        if (cookie != null) response.setHeader("Set-Cookie", cookie);
        server.enqueue(response);
    }

    private void enqueueServiceToken(String serviceToken) {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Set-Cookie", "miothealth_serviceToken=" + serviceToken)
                .setHeader("Location", server.url("/must-not-follow")));
    }

    private RecordedRequest takeRequest(String path) throws Exception {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(path, request.getRequestUrl().encodedPath());
        return request;
    }
}
