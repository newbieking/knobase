package com.knobase.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.knobase.business.ApiModels.*;

@Component
public class AiClient {
    private final HttpClient client;
    private final ObjectMapper json;
    private final String baseUrl;
    private final Duration timeout;

    public AiClient(ObjectMapper json, @Value("${knobase.ai.base-url}") String baseUrl,
                    @Value("${knobase.ai.timeout-seconds:90}") long seconds) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(seconds);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public record Answer(String answer, List<Citation> citations, double elapsed, String model, String mode) {}
    public record AiHealth(boolean up, String mode, String index, long chunks) {
        public static AiHealth down() { return new AiHealth(false, "local", "unavailable", 0); }
    }

    public String parse(String name, byte[] bytes) {
        JsonNode node = post("/internal/parse", Map.of("name", name, "data", Base64.getEncoder().encodeToString(bytes)));
        String text = text(node, "text");
        if (text.isBlank()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "文档未提取到可检索文本，请检查文件内容或使用带文字层的 PDF");
        return text;
    }

    public int countChunks(String content, int chunkSize) {
        JsonNode node = post("/internal/chunk", Map.of("content", content, "chunkSize", chunkSize));
        if (!node.path("chunkCount").isInt() || node.path("chunkCount").asInt() < 0) throw invalidResponse();
        return node.path("chunkCount").asInt();
    }

    public Answer query(String question, List<Document> documents, List<Message> history, Settings settings) {
        List<Map<String, String>> sources = documents.stream().map(doc -> Map.of(
                "id", doc.id(), "name", doc.name(), "content", doc.content(), "kbId", doc.kbId())).toList();
        List<Map<String, String>> messages = history.stream().map(message -> Map.of(
                "role", message.role(), "content", message.content())).toList();
        Map<String, Object> request = new HashMap<>();
        request.put("question", question);
        request.put("documents", sources);
        request.put("history", messages);
        // Server-owned workspace settings are authoritative, not request model/documents/ACL.
        request.put("model", settings.model());
        // Python rejects topK > 20; legacy stored settings may still hold larger values.
        request.put("topK", Math.max(1, Math.min(settings.topK(), 20)));
        request.put("temperature", settings.temperature());
        request.put("hybridSearch", settings.hybridSearch());
        request.put("reranking", settings.reranking());
        request.put("chunkSize", settings.chunkSize());
        JsonNode node = post("/internal/query", request);
        String answer = text(node, "answer");
        String model = text(node, "model");
        String mode = mode(node);
        if (answer.isBlank() || model.isBlank() || !node.path("citations").isArray()
                || !node.path("elapsed").isNumber() || !Double.isFinite(node.path("elapsed").asDouble())
                || node.path("elapsed").asDouble() < 0) throw invalidResponse();
        Map<String, Document> allowed = new HashMap<>();
        documents.forEach(doc -> allowed.put(doc.id(), doc));
        List<Citation> citations = new ArrayList<>();
        for (JsonNode cite : node.path("citations")) {
            String docId = text(cite, "documentId");
            if (!allowed.containsKey(docId) || !cite.path("page").canConvertToInt() || cite.path("page").asInt() < 1
                    || !cite.path("score").isNumber() || !Double.isFinite(cite.path("score").asDouble())) throw invalidResponse();
            citations.add(new Citation(text(cite, "id"), docId, allowed.get(docId).name(), cite.path("page").asInt(),
                    text(cite, "excerpt"), cite.path("score").asDouble()));
        }
        return new Answer(answer, citations, node.path("elapsed").asDouble(), model, mode);
    }

    public AiHealth health() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/health")).timeout(Duration.ofSeconds(3)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) return AiHealth.down();
            JsonNode body = json.readTree(response.body());
            String mode = mode(body);
            String status = body.path("status").asText("up");
            boolean up = status.equals("up") || status.equals("ok") || status.equals("healthy");
            if (!up) return AiHealth.down();
            return new AiHealth(true, mode, body.path("index").asText("unavailable"),
                    body.path("indexedChunks").asLong(0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AiHealth.down();
        } catch (Exception e) {
            return AiHealth.down();
        }
    }

    private JsonNode post(String path, Object payload) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), java.nio.charset.StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = "AI 服务处理失败（HTTP " + response.statusCode() + "）";
                try {
                    JsonNode body = json.readTree(response.body());
                    JsonNode detail = body.has("message") ? body.path("message") : body.path("detail");
                    if (detail.isTextual() && !detail.asText().isBlank())
                        message += "：" + detail.asText().substring(0, Math.min(300, detail.asText().length()));
                } catch (Exception ignored) { /* Never return a raw upstream HTML error page. */ }
                throw new ApiException(response.statusCode() == 400 || response.statusCode() == 422
                        ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY, message);
            }
            try {
                JsonNode result = json.readTree(response.body());
                if (result == null || !result.isObject()) throw invalidResponse();
                return result;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw invalidResponse();
            }
        } catch (HttpTimeoutException e) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI 服务响应超时，请稍后重试");
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 解析与检索服务不可用，请启动 127.0.0.1:9001 上的 Python 服务后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务请求已中断，请重试");
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual()) throw invalidResponse();
        return node.path(field).asText();
    }

    private String mode(JsonNode node) {
        String value = text(node, "mode");
        if (!value.equals("local") && !value.equals("connected")) throw invalidResponse();
        return value;
    }

    private ApiException invalidResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI 服务返回的数据格式无效，请检查解析与检索服务版本");
    }
}
