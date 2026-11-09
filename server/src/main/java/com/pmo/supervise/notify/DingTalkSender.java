package com.pmo.supervise.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.config.DingTalkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉工作通知（TECHNICAL §4.6）：发送永不抛异常，只返回渠道状态，
 * 保证钉钉故障不阻塞业务事务；未配置立即降级为 NOT_CONFIGURED。
 */
@Component
public class DingTalkSender {

    private static final Logger log = LoggerFactory.getLogger(DingTalkSender.class);

    private final DingTalkProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private volatile String accessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public DingTalkSender(DingTalkProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public List<String> missingKeys() {
        return properties.missingKeys();
    }

    public DingTalkStatus sendWorkNotice(String unionId, String title, String markdown, String url) {
        if (!isConfigured()) {
            return DingTalkStatus.NOT_CONFIGURED;
        }
        if (unionId == null || unionId.isBlank()) {
            return DingTalkStatus.UNBOUND;
        }
        try {
            String token = accessToken();
            if (token == null) {
                return DingTalkStatus.FAILED;
            }
            Map<String, Object> markdownBody = new LinkedHashMap<>();
            markdownBody.put("title", title);
            markdownBody.put("text", markdown + (url == null || url.isBlank() ? "" : "\n\n[查看详情](" + url + ")"));

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("msgtype", "markdown");
            msg.put("markdown", markdownBody);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agent_id", properties.getAgentId());
            payload.put("userid_list", unionId);
            payload.put("msg", msg);

            String response = restClient.post()
                    .uri(properties.getApiBase() + "/topapi/message/corpconversation/asyncsend_v2?access_token={token}", token)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response == null ? "{}" : response);
            int errcode = node.path("errcode").asInt(0);
            if (errcode == 0) {
                return DingTalkStatus.SENT;
            }
            log.warn("钉钉工作通知发送失败：errcode={}, errmsg={}", errcode, node.path("errmsg").asText(""));
            return DingTalkStatus.FAILED;
        } catch (Exception ex) {
            log.warn("钉钉工作通知发送异常：{}", ex.getMessage());
            return DingTalkStatus.FAILED;
        }
    }

    private String accessToken() {
        Instant now = Instant.now();
        if (accessToken != null && now.isBefore(tokenExpiresAt)) {
            return accessToken;
        }
        try {
            String response = restClient.get()
                    .uri(properties.getApiBase() + "/gettoken?appkey={key}&appsecret={secret}",
                            properties.getAppKey(), properties.getAppSecret())
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response == null ? "{}" : response);
            if (node.path("errcode").asInt(-1) != 0) {
                log.warn("钉钉 access_token 获取失败：errcode={}, errmsg={}",
                        node.path("errcode").asInt(-1), node.path("errmsg").asText(""));
                return null;
            }
            long expiresIn = node.path("expires_in").asLong(7200);
            accessToken = node.path("access_token").asText(null);
            tokenExpiresAt = now.plusSeconds(Math.max(expiresIn - 300, 60));
            return accessToken;
        } catch (Exception ex) {
            log.warn("钉钉 access_token 获取异常：{}", ex.getMessage());
            return null;
        }
    }
}
