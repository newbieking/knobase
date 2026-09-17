package com.knobase.business;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}

    public record KnowledgeBase(String id, String name, String description, String color, String icon,
                                int documentCount, int chunkCount, String status, String updatedAt,
                                String visibility, List<String> tags) {}
    public record Document(String id, String name, String kbId, String type, long size,
                           int chunkCount, String status, String updatedAt, String visibility, String content) {}
    public record Activity(String id, String type, String title, String description, String time) {}
    public record Citation(String id, String documentId, String name, int page, String excerpt, double score) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String id, String role, String content, List<Citation> citations, Double elapsed, String model) {}
    public record Session(String id, String title, String createdAt, List<Message> messages) {}
    public record Settings(
            @NotBlank @Size(max = 120) String model,
            @NotNull @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
            @NotNull @Min(1) @Max(20) Integer topK,
            @NotNull @Min(128) @Max(8192) Integer chunkSize,
            @NotNull Boolean hybridSearch,
            @NotNull Boolean reranking,
            @NotBlank @Size(max = 120) String workspaceName) {}
    public record TrendPoint(String date, long queries, long tokens) {}
    public record Stats(long queries, double queryChange, double latency, double successRate, List<TrendPoint> trend) {}
    public record Bootstrap(List<KnowledgeBase> knowledgeBases, List<Document> documents,
                            List<Activity> activities, List<Session> sessions, Settings settings,
                            Stats stats, String mode) {}
    public record Health(String status, String business, String ai, String mode, String aiIndex, long aiChunks) {}
    public record ChatResponse(String sessionId, Message message, String mode) {}
    public record ErrorResponse(String message) {}

    public record CreateKnowledgeBase(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @Size(min = 1, max = 60) String color,
            @Pattern(regexp = "team|private", message = "必须为 team 或 private") String visibility,
            @Size(max = 20) List<@NotBlank @Size(max = 40) String> tags) {}
    public record PatchKnowledgeBase(
            @Size(min = 1, max = 120) @Pattern(regexp = "(?s).*\\S.*", message = "名称不能为空") String name,
            @Size(max = 2000) String description,
            @Size(min = 1, max = 60) String color,
            @Pattern(regexp = "team|private", message = "必须为 team 或 private") String visibility,
            @Size(max = 20) List<@NotBlank @Size(max = 40) String> tags) {}
    public record ImportText(
            @NotBlank @Size(max = 80) String kbId,
            @Size(max = 255) String name,
            @NotBlank @Size(max = 10_485_760) String content) {}
    public record ChatRequest(
            @NotBlank @Size(max = 4000) String question,
            @Size(max = 80) String kbId,
            @Size(max = 80) String sessionId,
            @Size(max = 120) String model) {}
}
