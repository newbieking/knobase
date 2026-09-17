package com.knobase.business;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.knobase.business.ApiModels.*;
import static com.knobase.business.WorkspaceRepository.id;
import static com.knobase.business.WorkspaceRepository.now;

@Service
public class WorkspaceService {
    private static final long MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("pdf", "docx", "md", "txt", "csv");
    private static final int MAX_HISTORY_MESSAGES = 50;
    private final WorkspaceRepository repository;
    private final AiClient ai;
    private final TransactionTemplate transactions;

    public WorkspaceService(WorkspaceRepository repository, AiClient ai, TransactionTemplate transactions) {
        this.repository = repository;
        this.ai = ai;
        this.transactions = transactions;
    }

    public Bootstrap bootstrap() {
        String mode = ai.health().mode();
        return transactions.execute(status -> new Bootstrap(repository.knowledgeBases(), repository.documents(),
                repository.activities(), repository.sessions(), repository.settings(), repository.stats(), mode));
    }

    public Health health() {
        var health = ai.health();
        return new Health("up", "up", health.up() ? "up" : "down", health.mode());
    }

    @Transactional
    public KnowledgeBase createKnowledgeBase(CreateKnowledgeBase request) {
        KnowledgeBase kb = new KnowledgeBase(id("kb"), request.name().strip(),
                request.description() == null ? "" : request.description().strip(),
                request.color() == null ? "orange" : request.color(), "book", 0, 0, "ready", now(),
                request.visibility() == null ? "team" : request.visibility(), normalizeTags(request.tags()));
        repository.insertKnowledgeBase(kb);
        repository.addActivity("create", "创建了知识库「" + kb.name() + "」", "本地演示管理员创建了新的知识空间");
        return kb;
    }

    @Transactional
    public KnowledgeBase patchKnowledgeBase(String id, PatchKnowledgeBase request) {
        KnowledgeBase kb = repository.knowledgeBase(id);
        repository.updateKnowledgeBase(new KnowledgeBase(kb.id(), request.name() == null ? kb.name() : request.name().strip(),
                request.description() == null ? kb.description() : request.description().strip(),
                request.color() == null ? kb.color() : request.color(), kb.icon(), kb.documentCount(), kb.chunkCount(), kb.status(),
                now(), request.visibility() == null ? kb.visibility() : request.visibility(),
                request.tags() == null ? kb.tags() : normalizeTags(request.tags())));
        return repository.knowledgeBase(id);
    }

    private List<String> normalizeTags(List<String> tags) {
        return tags == null ? List.of() : tags.stream().map(String::strip).distinct().toList();
    }

    @Transactional
    public void deleteKnowledgeBase(String id) { repository.deleteKnowledgeBase(id); }

    public List<Document> upload(String kbId, List<MultipartFile> files) {
        if (kbId == null || kbId.isBlank()) throw ApiException.badRequest("请选择知识库");
        repository.knowledgeBase(kbId);
        if (files == null || files.isEmpty()) throw ApiException.badRequest("请通过 files 字段选择至少一个文件");
        // Validate the entire batch before making any parser requests or database changes.
        for (MultipartFile file : files) {
            filename(file.getOriginalFilename(), false);
            if (file.isEmpty()) throw ApiException.badRequest("不能上传空文件");
            if (file.getSize() > MAX_BYTES) throw tooLarge();
        }
        int chunkSize = repository.settings().chunkSize();
        List<Document> parsed = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = filename(file.getOriginalFilename(), false);
            try {
                String text = ai.parse(name, file.getBytes());
                parsed.add(new Document(id("doc"), name, kbId, extension(name), file.getSize(),
                        ai.countChunks(text, chunkSize), "ready", now(), "team", text));
            } catch (IOException e) {
                throw ApiException.badRequest("无法读取文件：" + name);
            }
        }
        // All-or-nothing: parser failures must never leave behind successful-looking documents.
        return transactions.execute(status -> {
            KnowledgeBase kb = repository.knowledgeBase(kbId);
            List<Document> saved = new ArrayList<>();
            for (Document doc : parsed) {
                Document current = new Document(doc.id(), doc.name(), doc.kbId(), doc.type(), doc.size(), doc.chunkCount(),
                        doc.status(), doc.updatedAt(), kb.visibility(), doc.content());
                repository.insertDocument(current);
                saved.add(current);
            }
            repository.touchKnowledgeBase(kbId);
            repository.addActivity("upload", "上传了 " + saved.size() + " 份文档", "已解析并加入「" + kb.name() + "」");
            return saved;
        });
    }

    public Document importText(ImportText request) {
        KnowledgeBase kb = repository.knowledgeBase(request.kbId());
        String name = filename(request.name(), true);
        long size = request.content().getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_BYTES) throw tooLarge();
        int chunks = ai.countChunks(request.content(), repository.settings().chunkSize());
        if (chunks == 0) throw ApiException.badRequest("文档内容不能为空");
        Document doc = new Document(id("doc"), name, kb.id(), extension(name), size, chunks,
                "ready", now(), kb.visibility(), request.content());
        // Counting happens outside the transaction, so the HTTP call cannot hold database locks.
        return transactions.execute(status -> {
            repository.knowledgeBase(kb.id());
            repository.insertDocument(doc);
            repository.touchKnowledgeBase(kb.id());
            repository.addActivity("upload", "导入了「" + name + "」", "文本已分段并加入「" + kb.name() + "」");
            return doc;
        });
    }

    public Document document(String id) { return repository.document(id); }

    @Transactional
    public void deleteDocument(String id) {
        Document doc = repository.document(id);
        repository.deleteDocument(id);
        repository.touchKnowledgeBase(doc.kbId());
    }

    public Document reindex(String id) {
        Document doc = repository.document(id);
        int chunks = ai.countChunks(doc.content(), repository.settings().chunkSize());
        if (chunks == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "文档没有可索引的文本");
        return transactions.execute(status -> {
            repository.reindexDocument(id, chunks);
            repository.touchKnowledgeBase(doc.kbId());
            repository.addActivity("index", "重新索引了「" + doc.name() + "」", "按当前分段大小生成 " + chunks + " 个文本片段");
            return repository.document(id);
        });
    }

    public ChatResponse chat(ChatRequest request) {
        if (request.kbId() != null && request.kbId().isBlank()) throw ApiException.badRequest("kbId 不能为空字符串");
        if (request.sessionId() != null && request.sessionId().isBlank()) throw ApiException.badRequest("sessionId 不能为空字符串");
        if (request.kbId() != null) repository.knowledgeBase(request.kbId());
        Session existing = request.sessionId() == null ? null : repository.session(request.sessionId(), false);
        List<Document> docs = repository.readyDocuments(request.kbId());
        Settings settings = repository.settings();
        String question = request.question().strip();
        AiClient.Answer answer;
        try {
            answer = ai.query(question, docs, history(existing), settings);
        } catch (ApiException e) {
            transactions.executeWithoutResult(status -> repository.recordQuery(false, 0, 0));
            throw e;
        }
        Message user = new Message(id("msg"), "user", question, null, null, null);
        Message assistant = new Message(id("msg"), "assistant", answer.answer(), answer.citations(), answer.elapsed(), answer.model());
        return transactions.execute(status -> {
            if (request.kbId() != null) repository.knowledgeBase(request.kbId());
            String sessionId;
            if (existing == null) {
                sessionId = id("session");
                int titleEnd = question.offsetByCodePoints(0, Math.min(question.codePointCount(0, question.length()), 40));
                repository.insertSession(new Session(sessionId, question.substring(0, titleEnd), now(), List.of(user, assistant)));
            } else {
                sessionId = existing.id();
                repository.appendMessages(sessionId, user, assistant);
            }
            repository.addActivity("chat", "完成了一次知识问答", question.substring(0, Math.min(question.length(), 250)));
            // The Python contract has no usage field; token trend is a character-based demo estimate, not billed usage.
            long estimatedTokens = Math.max(1, (question.codePointCount(0, question.length())
                    + answer.answer().codePointCount(0, answer.answer().length()) + 1L) / 2);
            repository.recordQuery(true, estimatedTokens, answer.elapsed());
            return new ChatResponse(sessionId, assistant, answer.mode());
        });
    }

    public List<Session> sessions() { return repository.sessions(); }

    @Transactional
    public void deleteSession(String id) { repository.deleteSession(id); }

    @Transactional
    public Settings saveSettings(Settings settings) {
        if (!Double.isFinite(settings.temperature())) throw ApiException.badRequest("temperature 必须为有限数字");
        repository.saveSettings(settings);
        return settings;
    }

    private List<Message> history(Session existing) {
        if (existing == null) return List.of();
        List<Message> prior = existing.messages();
        // Python rejects history beyond 50 messages; long sessions must stay answerable.
        return prior.size() <= MAX_HISTORY_MESSAGES ? prior : prior.subList(prior.size() - MAX_HISTORY_MESSAGES, prior.size());
    }

    private String filename(String original, boolean inline) {
        String name = original;
        if (inline && (name == null || name.isBlank())) name = "未命名文档.md";
        if (name == null || name.isBlank()) throw ApiException.badRequest("文件名不能为空");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (inline && !name.contains(".")) name += ".md";
        if (name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl))
            throw ApiException.badRequest("文件名无效或超过 255 个字符");
        if (!TYPES.contains(extension(name)))
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持 PDF、DOCX、MD、TXT 和 CSV 文件");
        return name;
    }

    private String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "单个文件的大小不得超过 10MB");
    }
}
