package com.knobase.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:business-api-test;DB_CLOSE_DELAY=-1",
        "knobase.ai.timeout-seconds=1"
})
@AutoConfigureMockMvc
class BusinessApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer AI = startAi();
    private static final AtomicReference<JsonNode> LAST_QUERY = new AtomicReference<>();
    private static final AtomicReference<JsonNode> LAST_CHUNK = new AtomicReference<>();
    private static final AtomicReference<JsonNode> LAST_PURGE = new AtomicReference<>();
    private static volatile int queryStatus;
    private static volatile boolean invalidCitation;
    private static volatile boolean slowQuery;
    private static volatile boolean badParse;
    private static volatile boolean badChunk;
    private static volatile int chunkCount = 1;
    private static volatile String healthMode = "local";
    private static volatile int healthStatus = 200;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceRepository repository;
    @Autowired SeedData seed;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("knobase.ai.base-url", () -> "http://127.0.0.1:" + AI.getAddress().getPort());
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM documents");
        jdbc.update("DELETE FROM knowledge_bases");
        jdbc.update("DELETE FROM chat_sessions");
        jdbc.update("DELETE FROM activities");
        jdbc.update("DELETE FROM daily_stats");
        jdbc.update("DELETE FROM workspace_settings");
        jdbc.update("DELETE FROM app_meta");
        seed.run(new DefaultApplicationArguments());
        LAST_QUERY.set(null);
        LAST_CHUNK.set(null);
        LAST_PURGE.set(null);
        chunkCount = 1;
        badChunk = false;
        queryStatus = 200;
        invalidCitation = false;
        slowQuery = false;
        badParse = false;
        healthMode = "local";
        healthStatus = 200;
    }

    @AfterAll
    static void stopAi() { AI.stop(0); }

    @Test
    void bootstrapHasExactContractAndUsefulPersistentSeed() throws Exception {
        JsonNode root = body(mvc.perform(get("/api/bootstrap")).andExpect(status().isOk()).andReturn());
        assertThat(fields(root)).containsExactlyInAnyOrder("knowledgeBases", "documents", "activities", "sessions", "settings", "stats", "mode");
        assertThat(root.path("knowledgeBases")).hasSize(6);
        assertThat(root.path("documents")).hasSize(24);
        assertThat(root.path("stats").path("queries").asLong()).isEqualTo(24860);
        assertThat(root.path("stats").path("trend")).hasSize(30);
        assertThat(root.path("mode").asText()).isEqualTo("local");
        assertThat(fields(root.path("knowledgeBases").get(0))).containsExactlyInAnyOrder(
                "id", "name", "description", "color", "icon", "documentCount", "chunkCount", "status", "updatedAt", "visibility", "tags");
        assertThat(fields(root.path("documents").get(0))).containsExactlyInAnyOrder(
                "id", "name", "kbId", "type", "size", "chunkCount", "status", "updatedAt", "visibility", "content");
        root.path("knowledgeBases").forEach(kb -> assertThat(kb.path("documentCount").asInt()).isEqualTo(4));
        assertThat(repository.document("doc-policy-leave").content()).contains("年休假 5 天", "年休假 10 天", "年休假 15 天");
        mvc.perform(delete("/api/knowledge-bases/kb-team")).andExpect(status().isNoContent());
        seed.run(new DefaultApplicationArguments());
        assertThat(repository.knowledgeBases()).hasSize(5);
        assertThat(repository.documents()).hasSize(20);
        assertThat(repository.stats().queries()).isEqualTo(24860);
    }

    @Test
    void knowledgeBaseCrudAggregatesAndCascades() throws Exception {
        JsonNode kb = createKb("集成测试库", "private");
        String id = kb.path("id").asText();
        assertThat(kb.path("icon").asText()).isEqualTo("book");
        assertThat(kb.path("documentCount").asInt()).isZero();
        JsonNode doc = importText(id, "知识卡.md", "测试内容，说明持久化和事务。");
        assertThat(doc.path("visibility").asText()).isEqualTo("private");
        mvc.perform(patch("/api/knowledge-bases/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"更新后的知识库\",\"visibility\":\"team\",\"tags\":[\"测试\",\"API\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.chunkCount").value(1)).andExpect(jsonPath("$.tags[1]").value("API"));
        assertThat(repository.document(doc.path("id").asText()).visibility()).isEqualTo("team");
        mvc.perform(delete("/api/knowledge-bases/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/documents/" + doc.path("id").asText() + "/download"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isString());
        mvc.perform(delete("/api/knowledge-bases/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void importsDefaultMarkdownDownloadsUtf8AndActuallyReindexes() throws Exception {
        String text = "差旅结束后30天内提交申请。餐补每日上限500元。\n".repeat(35);
        JsonNode doc = importText("kb-product", null, text);
        assertThat(doc.path("name").asText()).isEqualTo("未命名文档.md");
        assertThat(doc.path("size").asLong()).isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
        int originalChunks = doc.path("chunkCount").asInt();
        assertThat(doc.path("chunkCount").asInt()).isEqualTo(chunkCount);
        assertThat(fields(LAST_CHUNK.get())).containsExactlyInAnyOrder("content", "chunkSize");
        assertThat(LAST_CHUNK.get().path("chunkSize").asInt()).isEqualTo(512);
        String id = doc.path("id").asText();
        MvcResult download = mvc.perform(get("/api/documents/" + id + "/download"))
                .andExpect(status().isOk()).andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".txt"))).andReturn();
        assertThat(download.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(text);
        saveSettings(128);
        chunkCount = 9;
        JsonNode indexed = body(mvc.perform(post("/api/documents/" + id + "/reindex"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ready")).andReturn());
        assertThat(indexed.path("chunkCount").asInt()).isEqualTo(9);
        assertThat(indexed.path("chunkCount").asInt()).isGreaterThan(originalChunks);
        assertThat(LAST_CHUNK.get().path("chunkSize").asInt()).isEqualTo(128);
        assertThat(LAST_CHUNK.get().path("content").asText()).isEqualTo(text);
        assertThat(repository.settings().chunkSize()).isEqualTo(128);
        assertThat(LAST_PURGE.get().path("documentId").asText()).isEqualTo(id);
        LAST_PURGE.set(null);
        mvc.perform(delete("/api/documents/" + id)).andExpect(status().isNoContent());
        assertThat(LAST_PURGE.get().path("documentId").asText()).isEqualTo(id);
        mvc.perform(post("/api/documents/" + id + "/reindex")).andExpect(status().isNotFound());
    }

    @Test
    void uploadsMultipleFilesThroughActualHttpParser() throws Exception {
        var first = new MockMultipartFile("files", "测试.MD", "text/markdown", "第一份测试内容".getBytes(StandardCharsets.UTF_8));
        var second = new MockMultipartFile("files", "另一个.txt", "text/plain", "第二份测试内容".getBytes(StandardCharsets.UTF_8));
        JsonNode docs = body(mvc.perform(multipart("/api/documents").file(first).file(second).param("kbId", "kb-product"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).path("type").asText()).isEqualTo("md");
        assertThat(docs.get(0).path("content").asText()).isEqualTo("第一份测试内容");
        assertThat(docs.get(0).path("chunkCount").asInt()).isEqualTo(1);
        assertThat(repository.knowledgeBase("kb-product").documentCount()).isEqualTo(6);
    }

    @Test
    void failedBatchParserNeverLeavesPartialDocuments() throws Exception {
        var first = new MockMultipartFile("files", "ok.md", "text/plain", "okay".getBytes());
        var second = new MockMultipartFile("files", "fail.md", "text/plain", "failure".getBytes());
        mvc.perform(multipart("/api/documents").file(first).file(second).param("kbId", "kb-product"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").isString());
        assertThat(repository.documents()).hasSize(24);
        assertThat(repository.knowledgeBase("kb-product").documentCount()).isEqualTo(4);
    }

    @Test
    void rejectsInvalidUploadsAndMalformedParserResponse() throws Exception {
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "bad.exe", "application/octet-stream", new byte[]{1})).param("kbId", "kb-product"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "empty.txt", "text/plain", new byte[0])).param("kbId", "kb-product"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "large.txt", "text/plain", new byte[10 * 1024 * 1024 + 1])).param("kbId", "kb-product"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/documents").param("kbId", "kb-product"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        badParse = true;
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "bad.md", "text/plain", new byte[]{1})).param("kbId", "kb-product"))
                .andExpect(status().isBadGateway());
        assertThat(repository.documents()).hasSize(24);
    }

    @Test
    void chatSelectsServerDocumentsAndSettingsThenPersistsHistoryAndStats() throws Exception {
        mvc.perform(patch("/api/knowledge-bases/kb-policy").contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"private\"}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE documents SET status='failed' WHERE id='doc-policy-security'");
        JsonNode reply = body(mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"年假有多少天？\",\"kbId\":\"kb-policy\",\"model\":\"caller-override\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message.role").value("assistant"))
                .andExpect(jsonPath("$.mode").value("local")).andReturn());
        String session = reply.path("sessionId").asText();
        JsonNode sent = LAST_QUERY.get();
        assertThat(fields(sent)).containsExactlyInAnyOrder("question", "documents", "history", "model", "topK", "temperature", "hybridSearch", "reranking", "chunkSize");
        assertThat(sent.path("model").asText()).isEqualTo(repository.settings().model());
        assertThat(sent.path("topK").asInt()).isEqualTo(5);
        assertThat(sent.path("chunkSize").asInt()).isEqualTo(repository.settings().chunkSize());
        assertThat(sent.path("documents")).hasSize(3);
        sent.path("documents").forEach(doc -> {
            assertThat(fields(doc)).containsExactlyInAnyOrder("id", "name", "content", "kbId");
            assertThat(doc.path("kbId").asText()).isEqualTo("kb-policy");
        });
        assertThat(sent.path("history")).isEmpty();
        assertThat(repository.session(session, false).messages()).hasSize(2);
        assertThat(repository.stats().queries()).isEqualTo(24861);
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("question", "如何申请？", "sessionId", session, "kbId", "kb-policy"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(session));
        assertThat(LAST_QUERY.get().path("history")).hasSize(2);
        assertThat(fields(LAST_QUERY.get().path("history").get(0))).containsExactlyInAnyOrder("role", "content");
        assertThat(repository.session(session, false).messages()).hasSize(4);
        assertThat(repository.stats().queries()).isEqualTo(24862);
        mvc.perform(get("/api/sessions")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(session));
        mvc.perform(delete("/api/sessions/" + session)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/sessions/" + session)).andExpect(status().isNotFound());
    }

    @Test
    void legacyTopKIsClampedAndLongHistoryIsTrimmedForPythonContract() throws Exception {
        repository.saveSettings(new ApiModels.Settings("demo", 0.3, 30, 512, true, true, "测试工作空间"));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"年假\"}"))
                .andExpect(status().isOk());
        assertThat(LAST_QUERY.get().path("topK").asInt()).isEqualTo(20);

        String session = body(mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"第一问\"}")).andExpect(status().isOk()).andReturn()).path("sessionId").asText();
        for (int round = 0; round < 26; round++) {
            repository.appendMessages(session, new ApiModels.Message(null, "user", "历史问题" + round, null, null, null),
                    new ApiModels.Message(null, "assistant", "历史回答" + round, null, null, null));
        }
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"再次提问\",\"sessionId\":\"" + session + "\"}"))
                .andExpect(status().isOk());
        assertThat(LAST_QUERY.get().path("history")).hasSize(50);
        assertThat(LAST_QUERY.get().path("history").get(0).path("content").asText()).isEqualTo("历史问题1");
        assertThat(LAST_QUERY.get().path("history").get(49).path("content").asText()).isEqualTo("历史回答25");
    }

    @Test
    void callerCannotInjectDocumentsOrAclOrUseMissingResources() throws Exception {
        for (String field : List.of("documents", "acl", "history")) {
            mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"test\",\"" + field + "\":[]}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        }
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\",\"kbId\":\"missing\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"test\",\"sessionId\":\"missing\"}"))
                .andExpect(status().isNotFound());
        assertThat(LAST_QUERY.get()).isNull();
    }

    @Test
    void chatFailureNeverSavesMadeUpMessagesAndRejectsForeignCitations() throws Exception {
        queryStatus = 500;
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"年假\"}"))
                .andExpect(status().isBadGateway());
        assertThat(repository.sessions()).hasSize(1);
        queryStatus = 200;
        invalidCitation = true;
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"年假\",\"kbId\":\"kb-policy\"}"))
                .andExpect(status().isBadGateway());
        assertThat(repository.sessions()).hasSize(1);
        assertThat(repository.stats().queries()).isEqualTo(24862);
    }

    @Test
    void chatTimeoutIsExplicitAndDoesNotSaveConversation() throws Exception {
        slowQuery = true;
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"年假\"}"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.message").isString());
        assertThat(repository.sessions()).hasSize(1);
    }

    @Test
    void missingParserReturns503WithoutFakeSuccess() {
        AiClient unavailable = new AiClient(JSON, "http://127.0.0.1:1", 1);
        assertThatThrownBy(() -> unavailable.parse("test.txt", "hello".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("不可用");
                });
        assertThat(unavailable.health().up()).isFalse();
    }

    @Test
    void healthAndBootstrapReportActualAiModeAndFailure() throws Exception {
        healthMode = "connected";
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("up"))
                .andExpect(jsonPath("$.business").value("up")).andExpect(jsonPath("$.ai").value("up"))
                .andExpect(jsonPath("$.mode").value("connected"));
        mvc.perform(get("/api/bootstrap")).andExpect(jsonPath("$.mode").value("connected"));
        healthStatus = 503;
        mvc.perform(get("/api/health")).andExpect(jsonPath("$.ai").value("down")).andExpect(jsonPath("$.mode").value("local"));
    }

    @Test
    void settingsValidationAndRequestErrorsHaveUniformShape() throws Exception {
        for (String request : List.of("{}", "{\"name\":\"   \"}", "{\"name\":\"test\",\"visibility\":\"public\"}", "{\"name\":\"test\",\"icon\":\"x\"}")) {
            JsonNode error = body(mvc.perform(post("/api/knowledge-bases").contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isBadRequest()).andReturn());
            assertThat(fields(error)).containsExactly("message");
        }
        mvc.perform(patch("/api/knowledge-bases/kb-product").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/text").contentType(MediaType.APPLICATION_JSON).content("{\"kbId\":\"kb-product\",\"content\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"demo\",\"temperature\":3,\"topK\":0,\"chunkSize\":10,\"hybridSearch\":true,\"reranking\":true,\"workspaceName\":\"测试\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"demo\",\"temperature\":0.3,\"topK\":21,\"chunkSize\":127,\"hybridSearch\":true,\"reranking\":true,\"workspaceName\":\"测试\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/not-a-route")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isString());
        mvc.perform(get("/api/chat")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.message").isString());
        assertThat(repository.settings().topK()).isEqualTo(5);
    }

    @Test
    void invalidChunkCountFromAiNeverPersistsAFakeIndexedDocument() throws Exception {
        badChunk = true;
        mvc.perform(post("/api/documents/text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kbId\":\"kb-product\",\"name\":\"片段.md\",\"content\":\"差旅报销需要发票。\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").isString());
        assertThat(repository.documents()).hasSize(24);
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("files", "ok.md", "text/plain",
                        "内容".getBytes(StandardCharsets.UTF_8))).param("kbId", "kb-product"))
                .andExpect(status().isBadGateway());
        assertThat(repository.documents()).hasSize(24);
        assertThat(repository.knowledgeBase("kb-product").chunkCount()).isPositive();
    }

    private JsonNode createKb(String name, String visibility) throws Exception {
        return body(mvc.perform(post("/api/knowledge-bases").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("name", name, "description", "测试知识库", "color", "blue", "visibility", visibility, "tags", List.of("测试")))))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode importText(String kbId, String name, String content) throws Exception {
        var request = JSON.createObjectNode().put("kbId", kbId).put("content", content);
        if (name != null) request.put("name", name);
        return body(mvc.perform(post("/api/documents/text").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isCreated()).andReturn());
    }

    private void saveSettings(int chunkSize) throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(new ApiModels.Settings("test-model", 0.2, 7, chunkSize, false, true, "测试工作空间"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chunkSize").value(chunkSize));
    }

    private JsonNode body(MvcResult result) throws IOException { return JSON.readTree(result.getResponse().getContentAsByteArray()); }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static HttpServer startAi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/health", exchange -> send(exchange, healthStatus, Map.of("status", "up", "mode", healthMode)));
            server.createContext("/internal/parse", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                if (request.path("name").asText().equals("fail.md")) {
                    send(exchange, 500, Map.of("message", "解析失败"));
                } else if (badParse) {
                    send(exchange, 200, Map.of("text", 123));
                } else {
                    String text = new String(Base64.getDecoder().decode(request.path("data").asText()), StandardCharsets.UTF_8);
                    send(exchange, 200, Map.of("text", text));
                }
            });
            server.createContext("/internal/chunk", exchange -> {
                LAST_CHUNK.set(JSON.readTree(exchange.getRequestBody()));
                if (badChunk) {
                    send(exchange, 200, Map.of("chunkCount", "many"));
                    return;
                }
                send(exchange, 200, Map.of("chunkCount", chunkCount));
            });
            server.createContext("/internal/index/purge", exchange -> {
                LAST_PURGE.set(JSON.readTree(exchange.getRequestBody()));
                send(exchange, 200, Map.of("status", "ok"));
            });
            server.createContext("/internal/query", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                LAST_QUERY.set(request);
                boolean invalid = invalidCitation;
                int status = queryStatus;
                if (slowQuery) {
                    try { new CountDownLatch(1).await(1600, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (status != 200) {
                    send(exchange, status, Map.of("message", "查询失败"));
                    return;
                }
                JsonNode doc = request.path("documents").get(0);
                List<Map<String, Object>> cites = doc == null ? List.of() : List.of(Map.of(
                        "id", "cite-1", "documentId", invalid ? "not-in-scope" : doc.path("id").asText(),
                        "name", doc.path("name").asText(), "page", 1, "excerpt", "引用的原文内容", "score", 0.95));
                send(exchange, 200, Map.of("answer", "根据制度，年假为5、10或15天。", "citations", cites,
                        "elapsed", 0.21, "model", request.path("model").asText(), "mode", "local"));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
    }
}
