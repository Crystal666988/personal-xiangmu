package com.tradeagent.modules.ai.skills;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.tools.ToolDefinition;
import com.tradeagent.modules.ai.tools.ToolDispatchService;
import com.tradeagent.modules.ai.tools.ToolExecutionTrace;
import com.tradeagent.modules.ai.tools.ToolRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能体 Skill 技能编排与生命周期管理服务
 */
@Slf4j
@Service
public class SkillService {

    @Resource
    private AgentSkillMapper skillMapper;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private ToolDispatchService toolDispatchService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initSkills() {
        try {
            Long count = skillMapper.selectCount(null);
            if (count == null || count == 0) {
                log.info("【Skill 技能中心】检测到技能表为空，正在预热种子技能...");
                seedDefaultSkills();
            }
        } catch (Exception e) {
            log.warn("【Skill 技能中心】初始化失败 (降级不影响主流程): {}", e.getMessage());
        }
    }

    private void seedDefaultSkills() {
        List<AgentSkill> defaults = List.of(
                AgentSkill.builder()
                        .id("solar_pricing_skill")
                        .name("光伏外贸阶梯核价与海运智算技能")
                        .category("COMMERCE")
                        .icon("SolarPower")
                        .description("基于采购套数动态匹配阶梯单瓦折扣，结合即期海运费与多币种中间价汇率，秒级生成标准 CIF 报价单与装柜配载方案。")
                        .systemPrompt("你是一名资深光伏外贸报价专家，负责针对客户提出的组件型号与采购套数，核算精准单瓦 FOB 单价、集装箱装柜配载与 CIF 运费。")
                        .toolIdsJson("[\"calculate_tier_price\", \"currency_exchange_rate\", \"estimate_freight_cif\"]")
                        .status("ACTIVE")
                        .invocationCount(18)
                        .successCount(18)
                        .avgLatencyMs(4.2)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build(),

                AgentSkill.builder()
                        .id("buyer_prospecting_skill")
                        .name("全球买家穿透与海关提单挖掘技能")
                        .category("PROSPECT")
                        .icon("Aim")
                        .description("根据海关 HS 编码（如 8541.4300）穿透目的港历史清关提单，挖掘海外高净值采购商画像、采购周期与供应链集中度。")
                        .systemPrompt("你是一名外贸海关大数据专家，负责穿透提单中真实收发货人名称、港口航线与申报品名，提炼核心买家画像。")
                        .toolIdsJson("[\"customs_data_lookup\"]")
                        .status("ACTIVE")
                        .invocationCount(12)
                        .successCount(12)
                        .avgLatencyMs(1.5)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build(),

                AgentSkill.builder()
                        .id("compliance_rag_skill")
                        .name("国际合规认证与多模态单证审核技能")
                        .category("KNOWLEDGE")
                        .icon("DocumentChecked")
                        .description("基于企业专属 RAG 知识库，对沙特 SASO、锂电池 UN38.3、UCP600 信用证软条款及 FORM E 关税协定进行精准比对与合规审查。")
                        .systemPrompt("你是一名国际贸易法务与单证风控专家，负责审查信用证最迟交单时限、防范软条款风险，并验证原产地证免税合规性。")
                        .toolIdsJson("[\"rag_vector_search\"]")
                        .status("ACTIVE")
                        .invocationCount(25)
                        .successCount(24)
                        .avgLatencyMs(8.6)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build(),

                AgentSkill.builder()
                        .id("predictive_trade_skill")
                        .name("预测型出海指标与数据库 Text-to-SQL 智算技能")
                        .category("VISION")
                        .icon("TrendCharts")
                        .description("将出海高管的自然语言战略提问转化为合法 SQL 查询与趋势预测，秒级聚合海外出货量大盘、汇率风险及客户复购率。")
                        .systemPrompt("你是一名出海数据科学家，负责将高管管理意图转译为分析型统计与预测模型，输出可视化指标建议。")
                        .toolIdsJson("[\"customs_data_lookup\", \"currency_exchange_rate\"]")
                        .status("ACTIVE")
                        .invocationCount(8)
                        .successCount(8)
                        .avgLatencyMs(5.8)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build()
        );

        for (AgentSkill s : defaults) {
            skillMapper.insert(s);
        }
        log.info("【Skill 技能中心】成功预置 4 项出海核心旗舰技能！");
    }

    /**
     * 获取系统中所有 Skill 列表 (附带绑定的原子工具元数据与标签)
     */
    public List<SkillDefinition> listAllSkills() {
        List<AgentSkill> skills = skillMapper.selectList(null);
        if (skills.isEmpty()) {
            seedDefaultSkills();
            skills = skillMapper.selectList(null);
        }

        return skills.stream().map(this::toSkillDefinition).collect(Collectors.toList());
    }

    /**
     * 启用/停用某个 Skill
     */
    public boolean toggleSkill(String skillId) {
        AgentSkill skill = skillMapper.selectById(skillId);
        if (skill == null) return false;

        String nextStatus = "ACTIVE".equalsIgnoreCase(skill.getStatus()) ? "INACTIVE" : "ACTIVE";
        skill.setStatus(nextStatus);
        skill.setUpdateTime(LocalDateTime.now());
        skillMapper.updateById(skill);
        log.info("【Skill 技能中心】技能 {} 状态变更为: {}", skillId, nextStatus);
        return true;
    }

    @Data
    public static class SkillExecutionResult {
        private String skillId;
        private String skillName;
        private boolean success;
        private long totalLatencyMs;
        private List<ToolExecutionTrace> toolTraces;
        private Map<String, Object> aggregatedPayload;
    }

    /**
     * 单独运行/调试某个 Skill (按需触发其绑定的全部原子工具并聚合返回)
     */
    public SkillExecutionResult executeSkill(String skillId, Map<String, Object> inputParams) {
        long start = System.currentTimeMillis();
        AgentSkill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new IllegalArgumentException("未找到该技能: " + skillId);
        }

        List<String> toolIds = parseToolIds(skill.getToolIdsJson());
        List<ToolExecutionTrace> traces = new ArrayList<>();
        Map<String, Object> combinedPayload = new LinkedHashMap<>();

        boolean allSuccess = true;
        for (String tid : toolIds) {
            try {
                ToolExecutionTrace tr = toolDispatchService.executeTool(tid, inputParams);
                traces.add(tr);
                if (tr != null && tr.getOutputResult() != null) {
                    combinedPayload.put(tid, tr.getOutputResult());
                }
            } catch (Exception e) {
                allSuccess = false;
                log.error("执行 Skill 工具 {} 异常: {}", tid, e.getMessage());
            }
        }

        long totalLatency = System.currentTimeMillis() - start;

        // 更新技能调用统计
        try {
            int prevInv = skill.getInvocationCount() != null ? skill.getInvocationCount() : 0;
            int prevSucc = skill.getSuccessCount() != null ? skill.getSuccessCount() : 0;
            double prevLat = skill.getAvgLatencyMs() != null ? skill.getAvgLatencyMs() : 0.0;

            int newInv = prevInv + 1;
            int newSucc = allSuccess ? prevSucc + 1 : prevSucc;
            double newLat = Math.round(((prevLat * prevInv + totalLatency) / newInv) * 10.0) / 10.0;

            skill.setInvocationCount(newInv);
            skill.setSuccessCount(newSucc);
            skill.setAvgLatencyMs(newLat);
            skill.setUpdateTime(LocalDateTime.now());
            skillMapper.updateById(skill);
        } catch (Exception e) {
            log.warn("更新技能统计失败: {}", e.getMessage());
        }

        SkillExecutionResult res = new SkillExecutionResult();
        res.setSkillId(skill.getId());
        res.setSkillName(skill.getName());
        res.setSuccess(allSuccess);
        res.setTotalLatencyMs(totalLatency);
        res.setToolTraces(traces);
        res.setAggregatedPayload(combinedPayload);
        return res;
    }

    private SkillDefinition toSkillDefinition(AgentSkill entity) {
        List<String> toolIds = parseToolIds(entity.getToolIdsJson());
        List<ToolDefinition> boundTools = toolIds.stream()
                .map(toolRegistry::getDefinition)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<String> tags = switch (entity.getId()) {
            case "solar_pricing_skill" -> List.of("商业算价", "阶梯折扣", "实时汇率", "集装箱配载");
            case "buyer_prospecting_skill" -> List.of("HS编码穿透", "买家提单画像", "供应链挖掘");
            case "compliance_rag_skill" -> List.of("SASO认证", "UN38.3报告", "UCP600审单", "FORM E原产地证");
            case "predictive_trade_skill" -> List.of("Text2SQL", "订单趋势预测", "外贸指标聚合", "出海大盘洞察");
            default -> List.of("出海智能体", "技能插件");
        };

        Map<String, Object> sampleInput = switch (entity.getId()) {
            case "solar_pricing_skill" -> Map.of("quantity", 2000, "model", "580W", "destinationPort", "Rotterdam", "terms", "CIF");
            case "buyer_prospecting_skill" -> Map.of("hsCode", "8541.4300", "country", "Netherlands");
            case "compliance_rag_skill" -> Map.of("query", "沙特 SASO 认证有效期及 Saber 清关要求");
            case "predictive_trade_skill" -> Map.of("targetMarket", "Europe", "productCategory", "Solar Module");
            default -> Map.of();
        };

        int inv = entity.getInvocationCount() != null ? entity.getInvocationCount() : 0;
        int succ = entity.getSuccessCount() != null ? entity.getSuccessCount() : 0;
        double succRate = inv > 0 ? Math.round(((double) succ / inv) * 1000.0) / 10.0 : 100.0;

        return SkillDefinition.builder()
                .id(entity.getId())
                .name(entity.getName())
                .category(entity.getCategory())
                .icon(entity.getIcon())
                .description(entity.getDescription())
                .systemPrompt(entity.getSystemPrompt())
                .toolIds(toolIds)
                .boundTools(boundTools)
                .status(entity.getStatus() != null ? entity.getStatus() : "ACTIVE")
                .tags(tags)
                .invocationCount(inv)
                .successRate(succRate)
                .avgLatencyMs(entity.getAvgLatencyMs() != null ? entity.getAvgLatencyMs() : 0.0)
                .sampleInput(sampleInput)
                .build();
    }

    private List<String> parseToolIds(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
