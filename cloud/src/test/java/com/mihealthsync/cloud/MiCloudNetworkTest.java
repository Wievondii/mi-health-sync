package com.mihealthsync.cloud;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class MiCloudNetworkTest {
    private static final String SECURITY = "dGVzdC1rZXktb25seQ==";
    private static final long FROM = 1_790_000_000L;
    private static final long TO = FROM + 86_400;
    private final MockWebServer server = new MockWebServer();
    private boolean stopped;
    private MiCloud cloud;
    private MiCloud.Session session;

    @Before
    public void startServer() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
                .addSubjectAlternativeName("::1").build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate()).build();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.start();
        HttpUrl localOrigin = server.url("/");
        cloud = new MiCloud(url -> {
            assertEquals("https", url.getProtocol());
            assertEquals("hlth.io.mi.com", url.getHost());
            HttpUrl target = localOrigin.resolve(url.getFile());
            assertNotNull(target);
            HttpsURLConnection connection = (HttpsURLConnection) target.url().openConnection();
            connection.setSSLSocketFactory(clientCertificates.sslSocketFactory());
            return connection;
        });
        JSONObject fixtureSession = new JSONObject().put("account", "fixture")
                .put("cookie", "serviceToken=fixture-only").put("security", SECURITY);
        session = MiCloud.Session.decode(fixtureSession.toString().getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void stopServer() throws Exception {
        if (!stopped) {
            server.shutdown();
            stopped = true;
        }
    }

    @Test
    public void followsServerCursorWithUnchangedTimeBounds() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    boolean continuation = range(request).has("next_key");
                    JSONObject body = new JSONObject()
                            .put("data_list", new org.json.JSONArray().put(new JSONObject().put("time", continuation ? FROM : TO)))
                            .put("has_more", !continuation);
                    if (!continuation) body.put("next_key", "fixture+page/2=");
                    return encrypted(request, new JSONObject().put("code", 0).put("result", body));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
        });
        MiCloud.Page first = cloud.fetch(session, MiCloud.Metric.SLEEP_DAILY, FROM, TO, null);
        assertTrue(first.hasMore);
        assertEquals(TO, first.rows.getJSONObject(0).getLong("time"));
        MiCloud.Page last = cloud.fetch(session, MiCloud.Metric.SLEEP_DAILY, FROM, TO, first.next);
        assertFalse(last.hasMore);
        assertEquals(FROM, last.rows.getJSONObject(0).getLong("time"));

        RecordedRequest firstRequest = takeRequest();
        RecordedRequest nextRequest = takeRequest();
        for (RecordedRequest request : new RecordedRequest[]{firstRequest, nextRequest}) {
            assertEquals("/app/v1/data/get_aggregated_fitness_data_by_time", request.getRequestUrl().encodedPath());
            assertEquals("cn", request.getHeader("region_tag"));
            JSONObject query = range(request);
            assertEquals("sleep", query.getString("key"));
            assertEquals("daily_report", query.getString("tag"));
            assertEquals(FROM, query.getLong("start_time"));
            assertEquals(TO, query.getLong("end_time"));
            assertTrue(query.getBoolean("reverse"));
        }
        assertFalse(range(firstRequest).has("next_key"));
        assertEquals("fixture+page/2=", range(nextRequest).getString("next_key"));
    }

    @Test
    public void distinguishesExpiredSessionForbiddenAndRateLimited() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(403));
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "41"));

        assertEquals(401, expectFailure().status);
        assertEquals(403, expectFailure().status);
        MiCloud.ProbeFailure limited = expectFailure();
        assertEquals(429, limited.status);
        assertEquals("41", limited.retryAfter);
        RecordedRequest request = takeRequest();
        assertEquals("/app/v1/data/get_fitness_data_by_time", request.getRequestUrl().encodedPath());
        assertFalse(range(request).has("tag"));
    }

    @Test
    public void distinguishesServerUnavailableFromConnectionFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        assertEquals(503, expectFailure().status);
        stopServer();
        IOException disconnected = assertThrows(IOException.class,
                () -> cloud.fetch(session, MiCloud.Metric.HEART_RATE, FROM, TO, null));
        assertFalse(disconnected instanceof MiCloud.ProbeFailure);
    }

    @Test
    public void usesSportEndpointAndItsOwnResponseList() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    JSONObject result = new JSONObject().put("sport_records",
                            new org.json.JSONArray().put(new JSONObject().put("time", TO)))
                            .put("has_more", false);
                    return encrypted(request, new JSONObject().put("code", 0).put("result", result));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
        });
        MiCloud.Page page = cloud.fetchSports(session, FROM, TO, null);
        assertFalse(page.hasMore);
        assertEquals(TO, page.rows.getJSONObject(0).getLong("time"));
        RecordedRequest request = takeRequest();
        assertEquals("/app/v1/data/get_sport_records_by_time", request.getRequestUrl().encodedPath());
        JSONObject parameters = range(request);
        assertFalse(parameters.has("key"));
        assertEquals(50, parameters.getInt("limit"));
    }

    private MiCloud.ProbeFailure expectFailure() {
        return assertThrows(MiCloud.ProbeFailure.class, () -> cloud.fetch(session, MiCloud.Metric.HEART_RATE, FROM, TO, null));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    private JSONObject range(RecordedRequest request) throws Exception {
        String encrypted = request.getRequestUrl().queryParameter("data");
        return new JSONObject(MiCloud.decrypt(encrypted, key(request)));
    }

    private MockResponse encrypted(RecordedRequest request, JSONObject json) throws Exception {
        Cipher cipher = Cipher.getInstance("ARCFOUR");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(request), "ARCFOUR"));
        cipher.update(new byte[1024]);
        byte[] encoded = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        return new MockResponse().setBody(Base64.getEncoder().encodeToString(encoded));
    }

    private byte[] key(RecordedRequest request) throws Exception {
        return MiCloud.healthKey(SECURITY, Base64.getDecoder().decode(request.getRequestUrl().queryParameter("_nonce")));
    }
}
