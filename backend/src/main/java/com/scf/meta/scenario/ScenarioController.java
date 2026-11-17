package com.scf.meta.scenario;

import com.scf.meta.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, String>>> list() {
        return ApiResponse.ok(List.of(
                Map.of("name", "same-name-diff-publisher", "title", "同名异发布者",
                        "description", "同名包在 npm 与内部包仓发布者/哈希不同且缺生态上下文，保留多个候选"),
                Map.of("name", "hash-conflict", "title", "哈希冲突",
                        "description", "SBOM 与锁文件坐标相同哈希不同，构建日志无法证明任一哈希，标为来源冲突且不签发已证实依赖"),
                Map.of("name", "private-mirror", "title", "私有镜像",
                        "description", "私有镜像字段变体单独映射，留下待核项并保持候选"),
                Map.of("name", "late-lockfile", "title", "构建后补传",
                        "description", "锁文件在构建截止点后补传且版本不同，原事实不改写，补传件登记为后续差异来源"),
                Map.of("name", "catalog-alias-revision", "title", "目录别名修订",
                        "description", "目录修订改变别名/发布者归属，监测器暂停下游视图，历史保留当时身份版本"),
                Map.of("name", "signature-revocation", "title", "签名撤销",
                        "description", "唯一依据的签名撤销后组件降为证据不足，新事实不能继续显示已证实"),
                Map.of("name", "replay-late-source", "title", "复演·晚到资料",
                        "description", "同一业务时点两个资料截止时间结论不同，变化归因到补传锁文件，原结果保持可回查"),
                Map.of("name", "replay-revision", "title", "复演·追溯修订",
                        "description", "更正版按生效与接收条件确定适用范围；后来确认的别名不用于解释此前的清单"),
                Map.of("name", "replay-validity-copies", "title", "复演·区间错开与转抄",
                        "description", "依赖边与身份有效区间无交集不能拼出链路；同一原始记录的转抄只算一份独立支持"),
                Map.of("name", "replay-exclusive-absence", "title", "复演·互斥与缺席",
                        "description", "互斥身份不合并证明；完整/残缺清单区分缺席与未知，排除结论保留判断条件"),
                Map.of("name", "replay-minimal-evidence", "title", "复演·精简组合",
                        "description", "多组替代支持下精简组合不可再删减；预算提前停止时如实报告覆盖范围")));
    }

    @PostMapping("/{name}/run")
    public ApiResponse<ScenarioService.ScenarioResult> run(@PathVariable String name) {
        return ApiResponse.ok(scenarioService.run(name));
    }
}
