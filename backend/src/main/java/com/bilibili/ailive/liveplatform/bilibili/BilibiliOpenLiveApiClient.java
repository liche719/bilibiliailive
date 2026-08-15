package com.bilibili.ailive.liveplatform.bilibili;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
class BilibiliOpenLiveApiClient {

    private final BilibiliOpenLiveProperties properties;
    private final BilibiliRequestSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    BilibiliOpenLiveApiClient(
            BilibiliOpenLiveProperties properties,
            BilibiliRequestSigner signer,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
    }

    BilibiliOpenLiveSession start() {
        StartData data = post(
                "/v2/app/start",
                Map.of("code", properties.identityCode(), "app_id", properties.appId()),
                new TypeReference<ApiResponse<StartData>>() {
                }
        ).data();
        if (data == null || data.gameInfo() == null || data.websocketInfo() == null || data.anchorInfo() == null) {
            throw new BilibiliOpenLiveException("Bilibili start response is missing required session data");
        }
        List<String> websocketUrls = data.websocketInfo().wssLinks() == null
                ? List.of()
                : data.websocketInfo().wssLinks().stream()
                        .filter(url -> url != null && !url.isBlank())
                        .toList();
        if (isBlank(data.gameInfo().gameId())
                || isBlank(data.websocketInfo().authBody())
                || websocketUrls.isEmpty()
                || data.anchorInfo().roomId() < 1
                || isBlank(data.anchorInfo().openId())) {
            throw new BilibiliOpenLiveException("Bilibili start response contains invalid session details");
        }
        return new BilibiliOpenLiveSession(
                data.gameInfo().gameId(),
                data.websocketInfo().authBody(),
                websocketUrls,
                data.anchorInfo().roomId(),
                data.anchorInfo().openId()
        );
    }

    void heartbeat(String gameId) {
        post(
                "/v2/app/heartbeat",
                Map.of("game_id", gameId),
                new TypeReference<ApiResponse<Map<String, Object>>>() {
                }
        );
    }

    void end(String gameId) {
        post(
                "/v2/app/end",
                Map.of("app_id", properties.appId(), "game_id", gameId),
                new TypeReference<ApiResponse<Map<String, Object>>>() {
                }
        );
    }

    private <T> ApiResponse<T> post(String path, Object requestBody, TypeReference<ApiResponse<T>> responseType) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(requestBody);
            BilibiliSignedHeaders signed = signer.sign(body);
            URI target = properties.apiBaseUrl().resolve(path);
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(properties.requestTimeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("x-bili-content-md5", signed.contentMd5())
                    .header("x-bili-timestamp", signed.timestamp())
                    .header("x-bili-signature-method", "HMAC-SHA256")
                    .header("x-bili-signature-nonce", signed.nonce())
                    .header("x-bili-accesskeyid", properties.accessKeyId())
                    .header("x-bili-signature-version", "1.0")
                    .header("Authorization", signed.authorization())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BilibiliOpenLiveException(
                        "Bilibili API returned HTTP status " + response.statusCode()
                );
            }
            ApiResponse<T> parsed = objectMapper.readValue(response.body(), responseType);
            if (parsed.code() != 0) {
                throw new BilibiliOpenLiveException(
                        "Bilibili API rejected request: code=" + parsed.code()
                                + ", message=" + parsed.message()
                                + ", requestId=" + parsed.requestId()
                );
            }
            return parsed;
        } catch (BilibiliOpenLiveException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BilibiliOpenLiveException("Bilibili API request or response processing failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BilibiliOpenLiveException("Bilibili API request was interrupted", exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ApiResponse<T>(
            int code,
            String message,
            @JsonProperty("request_id") String requestId,
            T data
    ) {
    }

    private record StartData(
            @JsonProperty("game_info") GameInfo gameInfo,
            @JsonProperty("websocket_info") WebsocketInfo websocketInfo,
            @JsonProperty("anchor_info") AnchorInfo anchorInfo
    ) {
    }

    private record GameInfo(@JsonProperty("game_id") String gameId) {
    }

    private record WebsocketInfo(
            @JsonProperty("auth_body") String authBody,
            @JsonProperty("wss_link") List<String> wssLinks
    ) {
    }

    private record AnchorInfo(
            @JsonProperty("room_id") long roomId,
            @JsonProperty("open_id") String openId
    ) {
    }
}
