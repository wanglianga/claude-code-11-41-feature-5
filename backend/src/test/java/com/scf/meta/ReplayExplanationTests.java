package com.scf.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scf.meta.explain.ExplainService;
import com.scf.meta.explain.ReplayItem;
import com.scf.meta.explain.ReplayItemRepository;
import com.scf.meta.explain.ReplayService;
import com.scf.meta.explain.ReplayTask;
import com.scf.meta.explain.ReplayTaskRepository;
import com.scf.meta.scenario.ScenarioService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 时点复演与解释服务验收测试（H2 内存库）。 */
@SpringBootTest
@ActiveProfiles("test")
class ReplayExplanationTests {

    @Autowired ScenarioService scenarioService;
    @Autowired ReplayService replayService;
    @Autowired ExplainService explainService;
    @Autowired ReplayTaskRepository replayTaskRepo;
    @Autowired ReplayItemRepository replayItemRepo;

    @Test
    void lateSourceTwoCutoffsYieldDifferentConclusions() {
        ScenarioService.ScenarioResult r = scenarioService.run("replay-late-source");
        assertAllPassed(r);
        String taskId = (String) r.objects.get("replayTaskId");
        // 原复演结果保持可回查：late-lib 在旧资料截止下仍是来源未收到
        List<ReplayItem> items = replayItemRepo.findByReplayTaskId(taskId);
        assertTrue(items.stream().anyMatch(i -> i.coordinate.contains("late-lib")
                && "UNKNOWN".equals(i.itemType)
                && "SOURCE_NOT_RECEIVED".equals(i.reasonCode)), "原结果应保持来源未收到结论");
    }

    @Test
    void retroactiveRevisionAppliesByEffectiveAndReceipt() {
        ScenarioService.ScenarioResult r = scenarioService.run("replay-revision");
        assertAllPassed(r);
    }

    @Test
    void staggeredValidityCannotFormChainAndCopiesDeduped() {
        ScenarioService.ScenarioResult r = scenarioService.run("replay-validity-copies");
        assertAllPassed(r);
        String taskId = (String) r.objects.get("replayTaskId");
        ReplayItem copied = replayItemRepo.findByReplayTaskId(taskId).stream()
                .filter(i -> i.coordinate.contains("copied-lib")).findFirst().orElseThrow();
        // 独立支持来源为 2 个（SBOM + 同一原始记录的转抄组），精简组合穷尽且均经核实
        Map<String, Object> explain = explainService.explain(copied.id, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> minimal = (Map<String, Object>) explain.get("minimal");
        assertEquals(true, minimal.get("exhaustive"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combos = (List<Map<String, Object>>) minimal.get("combinations");
        assertFalse(combos.isEmpty(), "应存在精简组合");
        assertTrue(combos.stream().allMatch(c -> Boolean.TRUE.equals(c.get("verified"))),
                "每组组合都应经过不可再删减核实");
    }

    @Test
    void absenceTaxonomyAndMutualExclusion() {
        ScenarioService.ScenarioResult r = scenarioService.run("replay-exclusive-absence");
        assertAllPassed(r);
        String taskId = (String) r.objects.get("replayTaskId");
        List<ReplayItem> items = replayItemRepo.findByReplayTaskId(taskId);
        // 冲突坐标不得出现已证实条目
        assertTrue(items.stream().noneMatch(i -> i.coordinate.contains("conflict-lib")
                && "PRESENT".equals(i.itemType)), "互斥身份不得合并出已证实记录");
        // 缺席与未知分类齐全
        assertTrue(items.stream().anyMatch(i -> "ABSENT".equals(i.itemType)
                && "NOT_IN_COMPLETE_SOURCE".equals(i.reasonCode)));
        assertTrue(items.stream().anyMatch(i -> "ABSENT".equals(i.itemType)
                && "EXCLUDED_BY_CONDITION".equals(i.reasonCode)));
        assertTrue(items.stream().anyMatch(i -> "UNKNOWN".equals(i.itemType)
                && "COVERAGE_INSUFFICIENT".equals(i.reasonCode)));
        assertTrue(items.stream().anyMatch(i -> "UNKNOWN".equals(i.itemType)
                && "FILE_NOT_PARSED".equals(i.reasonCode)));
        assertTrue(items.stream().anyMatch(i -> "UNKNOWN".equals(i.itemType)
                && "IDENTITY_UNDECIDED".equals(i.reasonCode)));
    }

    @Test
    void minimalCombinationsIrreducibleAndBudgetHonest() {
        ScenarioService.ScenarioResult r = scenarioService.run("replay-minimal-evidence");
        assertAllPassed(r);
        String taskId = (String) r.objects.get("replayTaskId");
        ReplayItem multi = replayItemRepo.findByReplayTaskId(taskId).stream()
                .filter(i -> i.coordinate.contains("multi-lib")).findFirst().orElseThrow();

        // 预算 1：只核实部分组合，未检查分支如实列出
        Map<String, Object> limited = explainService.explain(multi.id, 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> minimal = (Map<String, Object>) limited.get("minimal");
        assertEquals(false, minimal.get("exhaustive"), "预算提前停止不得声称穷尽");
        @SuppressWarnings("unchecked")
        List<String> unchecked = (List<String>) minimal.get("uncheckedBranches");
        assertFalse(unchecked.isEmpty(), "应列出尚未检查的分支");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> combos = (List<Map<String, Object>>) minimal.get("combinations");
        assertTrue(combos.size() <= 1, "预算内组合数不应超过预算");
        assertTrue(combos.stream().allMatch(c -> Boolean.TRUE.equals(c.get("verified"))));
    }

    @Test
    void replayIsDeterministicAcrossLateArrivals() {
        ScenarioService.ScenarioResult r = scenarioService.run("replay-late-source");
        assertAllPassed(r);
        String taskId = (String) r.objects.get("replayTaskId");
        ReplayTask task = replayTaskRepo.findById(taskId).orElseThrow();
        // 晚到资料已到达后，用相同输入重算，结论不变
        ReplayTask recomputed = replayService.replay(task.buildId, task.businessTime,
                task.knowledgeCutoff,
                new ReplayService.ReplayQuery(
                        List.of(new ReplayService.CoordQuery("npm", null, "late-lib", "2.0.0")),
                        List.of(), List.of()), "test");
        assertEquals(signature(taskId), signature(recomputed.id), "相同输入应得到相同结论");
    }

    @Test
    void exportContainsRecomputableInputs() {
        ScenarioService.ScenarioResult r = scenarioService.run("replay-late-source");
        assertAllPassed(r);
        String taskId = (String) r.objects.get("replayTaskId");
        Map<String, Object> export = explainService.export(taskId);
        assertNotNull(export.get("inputs"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) export.get("inputs");
        assertNotNull(inputs.get("buildId"));
        assertNotNull(inputs.get("businessTime"));
        assertNotNull(inputs.get("knowledgeCutoff"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) export.get("items");
        assertFalse(items.isEmpty(), "导出应包含结果条目");
    }

    private String signature(String taskId) {
        return replayItemRepo.findByReplayTaskId(taskId).stream()
                .map(i -> i.coordKey + "|" + i.itemType + "|" + i.reasonCode)
                .sorted().reduce((a, b) -> a + ";" + b).orElse("");
    }

    private static void assertAllPassed(ScenarioService.ScenarioResult r) {
        List<String> failed = r.checks.stream()
                .filter(c -> !(Boolean) c.get("passed"))
                .map(c -> c.get("name") + ": " + c.get("detail"))
                .toList();
        assertTrue(failed.isEmpty(), "场景 " + r.scenario + " 存在未通过检查: " + failed);
    }
}
