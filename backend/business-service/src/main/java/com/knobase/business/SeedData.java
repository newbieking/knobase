package com.knobase.business;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.knobase.business.ApiModels.*;

@Component
@ConditionalOnProperty(name = "knobase.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedData implements ApplicationRunner {
    // Shared with the offline retrieval evaluation so both sides measure the same corpus.
    private static final String DOCUMENT_RESOURCE = "seed/documents.json";

    private final WorkspaceRepository repository;
    private final ObjectMapper json;

    public SeedData(WorkspaceRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    record SeedDocument(String id, String kbId, String name, int days, int chunkCount, String content) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // A persistent marker, not an empty-table check: deleted demo data never reappears on restart.
        if (repository.seeded()) return;
        repository.saveSettings(new Settings("gpt-4o-mini", 0.3, 5, 512, true, true, "知序团队空间"));
        kb("kb-product", "产品知识库", "从产品入门到版本更新，让每一份产品知识触手可及。", "orange", "book", List.of("产品", "使用指南"), 0);
        kb("kb-engineering", "技术研发中心", "沉淀架构方案、研发规范与实践经验，构建团队的技术记忆。", "blue", "code", List.of("研发", "技术文档"), 1);
        kb("kb-policy", "企业规章制度", "汇集人事、财务与行政制度，快速找到工作中的确定答案。", "purple", "building", List.of("人事", "规章制度"), 2);
        kb("kb-support", "客户服务手册", "统一服务标准与问题处理流程，让每次客户沟通更有温度。", "green", "headphones", List.of("客服", "常见问题"), 3);
        kb("kb-marketing", "市场与品牌资料", "品牌表达、设计规范与市场洞察，让创意有据可依。", "pink", "megaphone", List.of("品牌", "市场"), 4);
        kb("kb-team", "团队协作空间", "连接项目经验、会议共识与团队方法，让协作自然发生。", "amber", "users", List.of("协作", "团队"), 5);

        try (InputStream input = new ClassPathResource(DOCUMENT_RESOURCE).getInputStream()) {
            for (SeedDocument seed : json.readValue(new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<SeedDocument>>() {})) {
                repository.insertDocument(new Document(seed.id(), seed.name(), seed.kbId(),
                        seed.name().substring(seed.name().lastIndexOf('.') + 1),
                        seed.content().getBytes(StandardCharsets.UTF_8).length, seed.chunkCount(), "ready",
                        ago(seed.days(), 30), "team", seed.content()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取演示语料 " + DOCUMENT_RESOURCE, e);
        }

        repository.insertActivity(new Activity("act-seed-1", "upload", "更新了「产品更新日志 v2.4」", "产品知识库 · 新增混合检索与重排说明", ago(0, 42)));
        repository.insertActivity(new Activity("act-seed-2", "chat", "查询了员工年假与差旅报销规定", "企业规章制度 · 回答已关联 2 份参考资料", ago(0, 95)));
        repository.insertActivity(new Activity("act-seed-3", "index", "完成了技术文档索引", "技术研发中心 · 架构设计与接口约定已更新", ago(0, 180)));
        repository.insertActivity(new Activity("act-seed-4", "upload", "上传了客户退款处理政策", "客户服务手册 · 客服团队的常见问题资料", ago(1, 70)));
        repository.insertActivity(new Activity("act-seed-5", "create", "创建了团队协作空间", "沉淀会议纪要、项目复盘与新人学习路线", ago(1, 140)));
        repository.insertActivity(new Activity("act-seed-6", "upload", "更新了品牌视觉设计规范", "市场与品牌资料 · 品牌色彩与内容表达规范", ago(2, 90)));
        repository.insertActivity(new Activity("act-seed-7", "index", "重新整理了企业制度片段", "企业规章制度 · 休假、报销与入职政策", ago(3, 120)));
        repository.insertActivity(new Activity("act-seed-8", "create", "建立了产品知识库", "开始构建团队的产品知识与使用指南", ago(5, 30)));
        repository.insertSession(new Session("session-demo-policy", "年假和差旅报销有哪些规定？", ago(0, 95), List.of(
                new Message("msg-demo-user", "user", "年假有多少天？出差后多久需要报销，餐补标准是什么？", null, null, null),
                new Message("msg-demo-assistant", "assistant", "根据示例制度：\n\n1. 年假按累计工作年限计算：满 1 年不满 10 年为 5 天，满 10 年不满 20 年为 10 天，满 20 年为 15 天；一般提前 3 个工作日申请。[1]\n2. 差旅结束后 30 天内提交完整报销申请，附审批单、有效发票与付款凭证。[2]\n3. 境内差旅餐补上限为每人每天 500 元，按实际合规支出审核，已统一供餐不得重复报销，并非固定发放的补贴。[2]\n\n以上是工作空间中的示例政策，个人适用情况请向 HR 或财务确认。",
                        List.of(new Citation("cite-demo-1", "doc-policy-leave", "员工休假与考勤管理制度.pdf", 1,
                                        "累计工作已满 1 年不满 10 年，年休假 5 天；已满 10 年不满 20 年，年休假 10 天；已满 20 年，年休假 15 天。", 0.96),
                                new Citation("cite-demo-2", "doc-policy-expense", "差旅与费用报销管理办法.docx", 1,
                                        "差旅结束后 30 天内提交完整报销申请。境内差旅餐费补助上限为每人每天 500 元，按实际合规支出和有效凭证审核。", 0.94)), 1.24, "local-demo"))));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long used = 0;
        for (int i = 0; i < 30; i++) {
            long queries = i == 29 ? 24860 - used : 680 + i * 8L + (i % 5) * 12L;
            used += queries;
            long successes = Math.round(queries * 0.992);
            repository.seedStats(today.minusDays(29 - i), queries, successes,
                    queries * (930 + (i % 7) * 67L), successes * (1.1 + (i % 6) * 0.05));
        }
        repository.markSeeded();
    }

    private void kb(String id, String name, String description, String color, String icon, List<String> tags, int days) {
        repository.insertKnowledgeBase(new KnowledgeBase(id, name, description, color, icon, 0, 0, "ready", ago(days, 15), "team", tags));
    }

    private String ago(int days, int minutes) {
        return Instant.now().minus(days, ChronoUnit.DAYS).minus(minutes, ChronoUnit.MINUTES).toString();
    }
}
