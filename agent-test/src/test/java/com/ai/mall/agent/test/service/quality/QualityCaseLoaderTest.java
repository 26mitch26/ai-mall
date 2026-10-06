package com.ai.mall.agent.test.service.quality;

import com.ai.mall.agent.test.config.AgentTestConfig;
import com.ai.mall.agent.test.model.QualityCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测集加载与取样策略。
 *
 * <p>最关键的一条是"类别轮询取样"：默认只跑 12 条，如果只是取前 12 条，
 * 样本会几乎全是政策问答，注入/拒答/边界这些最该盯的能力面一次都测不到。
 */
class QualityCaseLoaderTest {

    private AgentTestConfig config;
    private QualityCaseLoader loader;

    @BeforeEach
    void setUp() {
        config = new AgentTestConfig();
        loader = new QualityCaseLoader(new ObjectMapper(), config);
        loader.load();
    }

    @Test
    void goldSetIsLoadedWithReferenceAnswers() {
        List<QualityCase> all = loader.all();
        assertTrue(all.size() >= 100, "gold 集应完整加载，实际 " + all.size());
        assertTrue(all.stream().anyMatch(c -> c.getReferenceAnswer() != null
                && !c.getReferenceAnswer().isBlank()),
                "referenceAnswer 必须被加载——它正是 judge 打分的依据");
        assertTrue(all.stream().anyMatch(c -> c.isExpectedRefusal()), "应包含期望拒答的用例");
        assertTrue(all.stream().anyMatch(c -> c.isExpectedBlocked()), "应包含注入拦截用例");
    }

    @Test
    void selectRespectsMaxCasesAndSpreadsAcrossCategories() {
        config.getQuality().setMaxCases(10);
        List<QualityCase> selected = loader.select();

        assertEquals(10, selected.size());
        Set<String> categories = selected.stream().map(QualityCase::getCategory).collect(Collectors.toSet());
        assertTrue(categories.size() >= 3,
                "类别轮询取样应覆盖多个能力面，实际只覆盖 " + categories);
    }

    @Test
    void selectHonoursSplitFilter() {
        config.getQuality().setSplit("dev");
        config.getQuality().setMaxCases(20);
        List<QualityCase> selected = loader.select();
        assertFalse(selected.isEmpty());
        assertTrue(selected.stream().allMatch(c -> "dev".equals(c.getSplit())));
    }

    @Test
    void selectExcludesFollowUpCasesByDefault() {
        config.getQuality().setMaxCases(30);
        List<QualityCase> selected = loader.select();
        assertTrue(selected.stream().noneMatch(QualityCase::isFollowUp),
                "多轮追问用例默认不纳入：无法可靠复现历史上下文，给出的结论不可信");
    }

    @Test
    void selectReturnsWholePoolWhenBudgetIsLargeEnough() {
        config.getQuality().setSplit("dev");
        config.getQuality().setIncludeFollowUp(true);
        config.getQuality().setMaxCases(1000);
        int devTotal = (int) loader.all().stream()
                .filter(c -> "dev".equals(c.getSplit()))
                .count();
        assertEquals(devTotal, loader.select().size());
    }
}
