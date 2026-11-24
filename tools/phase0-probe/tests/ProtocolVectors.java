package com.mihealthsync.cloud;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** 对照 mifit 的离线合成签名及响应向量，测试代码不打入 APK。 */
public final class ProtocolVectors {
    public static void main(String[] args) throws Exception {
        byte[] security = new byte[16];
        byte[] nonce = new byte[12];
        for (int i = 0; i < security.length; i++) security[i] = (byte) i;
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (i + 16);
        byte[] key = MiCloud.healthKey(base64(security), nonce);
        check("会话签名派生", base64(key), "3Cf46O4tCKK8y7Lb1sjgf/uhlBAfw0WMNN7VX3LAlxo=");
        Map<String, String> parameters = MiCloud.signedParams("{\"key\":\"heart_rate\",\"start_time\":1,\"end_time\":2}",
                nonce, key, "/app/v1/data/get_fitness_data_by_time");
        check("心率查询加密", parameters.get("data"), "JNbpDfhqoS9Q8TRB73kM6RC6LmQwlBRTP/x+foM9crV+W+RaET9RAo0FHKJtKHo0");
        check("加密序列", parameters.get("rc4_hash__"), "xbc6kZEzkKLoaJ6cbsaNn1xsSD4gjh+5Kzd5Bg==");
        check("最终签名", parameters.get("signature"), "kXIza1GUipdZ4CCRF2ZrEWeDREc=");
        byte[] referenceKey = MiCloud.healthKey(base64("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)), new byte[12]);
        check("响应解密", MiCloud.decrypt("LhVyEjKbby+v4g4mo55ANbwasNB1exlAkxoxkGibzA==", referenceKey), "{\"code\":0,\"result\":{\"ok\":true}}");
        System.out.println("5 项离线协议向量通过。");
    }

    private static void check(String label, String actual, String expected) {
        if (!expected.equals(actual)) throw new AssertionError(label + "不一致");
    }

    private static String base64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
}
