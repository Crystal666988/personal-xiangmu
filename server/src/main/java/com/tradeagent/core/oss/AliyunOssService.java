package com.tradeagent.core.oss;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * 阿里云 OSS 云端对象存储服务
 * 基于标准 RFC 1123 与 HMAC-SHA1 纯 JDK 实现，零依赖冲突
 */
@Slf4j
@Service
public class AliyunOssService {

    @Value("${aliyun.oss.access-key-id:${ALIYUN_OSS_ACCESS_KEY_ID:}}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret:${ALIYUN_OSS_ACCESS_KEY_SECRET:}}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name:${ALIYUN_OSS_BUCKET_NAME:life-agent-imgsearch}}")
    private String bucketName;

    @Value("${aliyun.oss.endpoint:${ALIYUN_OSS_ENDPOINT:oss-cn-guangzhou.aliyuncs.com}}")
    private String endpoint;

    private static final DateTimeFormatter RFC1123_FORMATTER = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);

    /**
     * 上传二进制文件到阿里云 OSS 并返回 7 天有效期的公网安全访问 URL
     */
    public String uploadFile(String originalFileName, byte[] data, String contentType) {
        if (data == null || data.length == 0) {
            return null;
        }

        if (accessKeyId == null || accessKeyId.isBlank() || accessKeySecret == null || accessKeySecret.isBlank()) {
            log.info("【阿里云 OSS】未配置 ALIYUN_OSS_ACCESS_KEY_ID / SECRET，跳过云端对象存储上传");
            return null;
        }

        String safeName = (originalFileName != null && !originalFileName.isBlank()) ? originalFileName : "file.bin";
        String dateDir = DateTimeFormatter.ofPattern("yyyyMMdd").format(ZonedDateTime.now(ZoneOffset.UTC));
        String uniqueId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String objectKey = "tradeagent/" + dateDir + "/" + uniqueId + "_" + safeName;

        String safeContentType = (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream";
        String dateStr = RFC1123_FORMATTER.format(ZonedDateTime.now(ZoneOffset.UTC));

        try {
            // 1. 计算 HMAC-SHA1 签名
            String canonicalizedResource = "/" + bucketName + "/" + objectKey;
            String stringToSign = "PUT\n\n" + safeContentType + "\n" + dateStr + "\n" + canonicalizedResource;

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] signBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signBytes);
            String authHeader = "OSS " + accessKeyId + ":" + signature;

            // 2. 发起 HTTP PUT 请求上传
            String uploadUrl = "http://" + bucketName + "." + endpoint + "/" + objectKey;
            URL url = new URL(uploadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", safeContentType);
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            conn.setRequestProperty("Date", dateStr);
            conn.setRequestProperty("Authorization", authHeader);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 201) {
                log.info("【阿里云 OSS】文件成功上传: {} -> {}", originalFileName, objectKey);
                // 3. 生成 7 天有效期的已签名访问 URL
                return generateSignedUrl(objectKey, 7 * 24 * 3600);
            } else {
                log.error("【阿里云 OSS】上传失败 HTTP {}: {}", responseCode, conn.getResponseMessage());
                return null;
            }
        } catch (Exception e) {
            log.error("【阿里云 OSS】上传过程异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 生成带签名的 GET 访问 URL
     */
    public String generateSignedUrl(String objectKey, long expireSeconds) {
        long expires = (System.currentTimeMillis() / 1000) + expireSeconds;
        String canonicalizedResource = "/" + bucketName + "/" + objectKey;
        String stringToSign = "GET\n\n\n" + expires + "\n" + canonicalizedResource;

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] signBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signBytes);
            String encodedSig = URLEncoder.encode(signature, StandardCharsets.UTF_8);

            return "http://" + bucketName + "." + endpoint + "/" + objectKey +
                    "?OSSAccessKeyId=" + accessKeyId +
                    "&Expires=" + expires +
                    "&Signature=" + encodedSig;
        } catch (Exception e) {
            log.error("【阿里云 OSS】生成签名 URL 失败: {}", e.getMessage());
            return "http://" + bucketName + "." + endpoint + "/" + objectKey;
        }
    }
}
