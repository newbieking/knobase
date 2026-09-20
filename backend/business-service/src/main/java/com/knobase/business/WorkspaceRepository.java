package com.knobase.business;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.knobase.business.ApiModels.*;

@Repository
public class WorkspaceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final TypeReference<List<String>> TAGS = new TypeReference<>() {};
    private static final TypeReference<List<Message>> MESSAGES = new TypeReference<>() {};
    private static final String KB_SELECT = """
            SELECT k.*,
              (SELECT COUNT(*) FROM documents d WHERE d.kb_id=k.id) AS doc_count,
              (SELECT COALESCE(SUM(d.chunk_count),0) FROM documents d WHERE d.kb_id=k.id) AS chunks,
              (SELECT COUNT(*) FROM documents d WHERE d.kb_id=k.id AND d.status='processing') AS processing
            FROM knowledge_bases k
            """;

    public WorkspaceRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public static String id(String prefix) { return prefix + "_" + UUID.randomUUID(); }
    public static String now() { return Instant.now().toString(); }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize stored data", e); }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored data", e); }
    }

    private KnowledgeBase mapKb(ResultSet rs, int row) throws SQLException {
        return new KnowledgeBase(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("color"), rs.getString("icon"), rs.getInt("doc_count"), rs.getInt("chunks"),
                rs.getInt("processing") > 0 ? "indexing" : "ready", rs.getString("updated_at"),
                rs.getString("visibility"), decode(rs.getString("tags"), TAGS));
    }

    private final RowMapper<Document> documentMapper = (rs, row) -> new Document(
            rs.getString("id"), rs.getString("name"), rs.getString("kb_id"), rs.getString("doc_type"),
            rs.getLong("size_bytes"), rs.getInt("chunk_count"), rs.getString("status"),
            rs.getString("updated_at"), rs.getString("visibility"), rs.getString("content"));

    public List<KnowledgeBase> knowledgeBases() {
        return jdbc.query(KB_SELECT + " ORDER BY k.updated_at DESC, k.id", this::mapKb);
    }

    public KnowledgeBase knowledgeBase(String id) {
        return jdbc.query(KB_SELECT + " WHERE k.id=?", this::mapKb, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("知识库"));
    }

    public void insertKnowledgeBase(KnowledgeBase kb) {
        jdbc.update("INSERT INTO knowledge_bases(id,name,description,color,icon,updated_at,visibility,tags) VALUES(?,?,?,?,?,?,?,?)",
                kb.id(), kb.name(), kb.description(), kb.color(), kb.icon(), kb.updatedAt(), kb.visibility(), encode(kb.tags()));
    }

    public void updateKnowledgeBase(KnowledgeBase kb) {
        int count = jdbc.update("UPDATE knowledge_bases SET name=?,description=?,color=?,updated_at=?,visibility=?,tags=? WHERE id=?",
                kb.name(), kb.description(), kb.color(), kb.updatedAt(), kb.visibility(), encode(kb.tags()), kb.id());
        if (count == 0) throw ApiException.notFound("知识库");
        jdbc.update("UPDATE documents SET visibility=? WHERE kb_id=?", kb.visibility(), kb.id());
    }

    public void touchKnowledgeBase(String id) {
        if (jdbc.update("UPDATE knowledge_bases SET updated_at=? WHERE id=?", now(), id) == 0)
            throw ApiException.notFound("知识库");
    }

    public void deleteKnowledgeBase(String id) {
        if (jdbc.update("DELETE FROM knowledge_bases WHERE id=?", id) == 0) throw ApiException.notFound("知识库");
    }

    public List<Document> documents() {
        return jdbc.query("SELECT * FROM documents ORDER BY updated_at DESC, id", documentMapper);
    }

    public List<Document> readyDocuments(String kbId) {
        // This is an explicitly local, all-admin workspace: private documents are accessible here.
        if (kbId == null) return jdbc.query("SELECT * FROM documents WHERE status='ready' ORDER BY updated_at DESC,id", documentMapper);
        return jdbc.query("SELECT * FROM documents WHERE status='ready' AND kb_id=? ORDER BY updated_at DESC,id", documentMapper, kbId);
    }

    public List<String> documentIds(String kbId) {
        return jdbc.queryForList("SELECT id FROM documents WHERE kb_id=?", String.class, kbId);
    }

    public Document document(String id) {
        return jdbc.query("SELECT * FROM documents WHERE id=?", documentMapper, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("文档"));
    }

    public void insertDocument(Document doc) {
        jdbc.update("INSERT INTO documents(id,name,kb_id,doc_type,size_bytes,chunk_count,status,updated_at,visibility,content) VALUES(?,?,?,?,?,?,?,?,?,?)",
                doc.id(), doc.name(), doc.kbId(), doc.type(), doc.size(), doc.chunkCount(), doc.status(),
                doc.updatedAt(), doc.visibility(), doc.content());
    }

    public void reindexDocument(String id, int chunks) {
        if (jdbc.update("UPDATE documents SET chunk_count=?,status='ready',updated_at=? WHERE id=?", chunks, now(), id) == 0)
            throw ApiException.notFound("文档");
    }

    public void deleteDocument(String id) {
        if (jdbc.update("DELETE FROM documents WHERE id=?", id) == 0) throw ApiException.notFound("文档");
    }

    public Settings settings() {
        String value = jdbc.queryForObject("SELECT settings_json FROM workspace_settings WHERE id=1", String.class);
        return decode(value, new TypeReference<>() {});
    }

    public void saveSettings(Settings settings) {
        jdbc.update("MERGE INTO workspace_settings(id,settings_json) KEY(id) VALUES(1,?)", encode(settings));
    }

    private Session mapSession(ResultSet rs, int row) throws SQLException {
        return new Session(rs.getString("id"), rs.getString("title"), rs.getString("created_at"),
                decode(rs.getString("messages_json"), MESSAGES));
    }

    public List<Session> sessions() {
        return jdbc.query("SELECT * FROM chat_sessions ORDER BY updated_at DESC,id", this::mapSession);
    }

    public Session session(String id, boolean lock) {
        return jdbc.query("SELECT * FROM chat_sessions WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::mapSession, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("会话"));
    }

    public void insertSession(Session session) {
        jdbc.update("INSERT INTO chat_sessions(id,title,created_at,updated_at,messages_json) VALUES(?,?,?,?,?)",
                session.id(), session.title(), session.createdAt(), session.createdAt(), encode(session.messages()));
    }

    public void appendMessages(String id, Message user, Message assistant) {
        var existing = session(id, true);
        var messages = new ArrayList<>(existing.messages());
        messages.add(user);
        messages.add(assistant);
        jdbc.update("UPDATE chat_sessions SET messages_json=?,updated_at=? WHERE id=?", encode(messages), now(), id);
    }

    public void deleteSession(String id) {
        if (jdbc.update("DELETE FROM chat_sessions WHERE id=?", id) == 0) throw ApiException.notFound("会话");
    }

    public void addActivity(String type, String title, String description) {
        insertActivity(new Activity(id("act"), type, title, description, now()));
    }

    public void insertActivity(Activity activity) {
        jdbc.update("INSERT INTO activities(id,activity_type,title,description,activity_time) VALUES(?,?,?,?,?)",
                activity.id(), activity.type(), activity.title(), activity.description(), activity.time());
    }

    public List<Activity> activities() {
        return jdbc.query("SELECT * FROM activities ORDER BY activity_time DESC,id LIMIT 100",
                (rs, row) -> new Activity(rs.getString("id"), rs.getString("activity_type"), rs.getString("title"),
                        rs.getString("description"), rs.getString("activity_time")));
    }

    public boolean seeded() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*) > 0 FROM app_meta WHERE meta_key='seed_version'", Boolean.class));
    }

    public void markSeeded() {
        jdbc.update("INSERT INTO app_meta(meta_key,meta_value) VALUES('seed_version','1')");
    }

    public void seedStats(LocalDate date, long queries, long successes, long tokens, double latency) {
        jdbc.update("INSERT INTO daily_stats(stat_date,query_count,success_count,token_count,latency_total) VALUES(?,?,?,?,?)",
                date, queries, successes, tokens, latency);
    }

    public void recordQuery(boolean success, long tokens, double elapsed) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        jdbc.update("""
                MERGE INTO daily_stats t USING (VALUES(CAST(? AS DATE))) s(d)
                ON t.stat_date=s.d
                WHEN NOT MATCHED THEN INSERT(stat_date,query_count,success_count,token_count,latency_total)
                VALUES(s.d,0,0,0,0)
                """, today);
        jdbc.update("UPDATE daily_stats SET query_count=query_count+1,success_count=success_count+?,token_count=token_count+?,latency_total=latency_total+? WHERE stat_date=?",
                success ? 1 : 0, tokens, success ? elapsed : 0, today);
    }

    public Stats stats() {
        var total = jdbc.queryForMap("SELECT COALESCE(SUM(query_count),0) AS q, COALESCE(SUM(success_count),0) AS s, COALESCE(SUM(latency_total),0) AS l FROM daily_stats");
        long queries = ((Number) total.get("q")).longValue();
        long successes = ((Number) total.get("s")).longValue();
        double latency = ((Number) total.get("l")).doubleValue();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var points = jdbc.query("SELECT stat_date,query_count,token_count FROM daily_stats WHERE stat_date>=? ORDER BY stat_date",
                (rs, row) -> new TrendPoint(rs.getDate(1).toLocalDate().toString(), rs.getLong(2), rs.getLong(3)), today.minusDays(29));
        var byDate = new java.util.HashMap<String, TrendPoint>();
        points.forEach(point -> byDate.put(point.date(), point));
        var trend = new ArrayList<TrendPoint>();
        for (int i = 29; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            trend.add(byDate.getOrDefault(date, new TrendPoint(date, 0, 0)));
        }
        long current = trend.subList(23, 30).stream().mapToLong(TrendPoint::queries).sum();
        long previous = trend.subList(16, 23).stream().mapToLong(TrendPoint::queries).sum();
        double change = previous == 0 ? 0 : round((current - previous) * 100.0 / previous);
        return new Stats(queries, change, successes == 0 ? 0 : round(latency / successes),
                queries == 0 ? 100 : round(successes * 100.0 / queries), trend);
    }

    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
}
