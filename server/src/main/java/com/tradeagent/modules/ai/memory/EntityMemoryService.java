package com.tradeagent.modules.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradeagent.modules.ai.memory.entity.CrmBuyerProfile;
import com.tradeagent.modules.ai.memory.entity.CrmBuyerProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨会话客户画像实体记忆服务 (Entity Memory Service)
 *
 * 遵循 Carl (youngyangyang04/llm-master) 核心架构规范：
 * 1. 【只填空不覆盖原则 (Fill-Blank-Only)】：从交互中提炼实体属性时，仅当字段为 NULL 时填充，不覆盖已有信息；
 * 2. 【人工加锁保护 (Human Verification Lock)】：人工确认过的字段进入加锁名单，AI 绝对禁止篡改；
 * 3. 【无缝降级与上下文注入】：支持注入 ZONE 2 用户不可篡改目标区，协助大模型维持跨轮跨会话一致认知。
 */
@Slf4j
@Service
public class EntityMemoryService {

    private final CrmBuyerProfileMapper profileMapper;
    private final ObjectMapper json;

    /** 内存级回退与高频缓存：tenantId:buyerId -> CrmBuyerProfile */
    private final Map<String, CrmBuyerProfile> fallbackStore = new ConcurrentHashMap<>();

    // 常用港口正则库 (支持前缀匹配、后缀"港"匹配)
    private static final Pattern PORT_PATTERN = Pattern.compile(
            "(?:到|发往|目的地|目的港|港口|走|运往|送至|port\\s*(?:to|of)?\\s*:?\\s*)([\\u4e00-\\u9fa5a-zA-Z\\s]{2,20}?)(?:港|port|\\s*,|\\s*\\.|\\s*，|\\s*。|$)|([\\u4e00-\\u9fa5a-zA-Z]{2,20}?)港",
            Pattern.CASE_INSENSITIVE
    );

    // 核心外贸枢纽港口词库保底
    private static final List<String> COMMON_WORLD_PORTS = List.of(
            "汉堡", "Hamburg", "鹿特丹", "Rotterdam", "迪拜", "Dubai", "杰贝阿里", "Jebel Ali",
            "洛杉矶", "Los Angeles", "安特卫普", "Antwerp", "热那亚", "Genoa", "巴塞罗那", "Barcelona",
            "瓦伦西亚", "Valencia", "新加坡", "Singapore", "宁波", "Ningbo", "上海", "Shanghai"
    );

    // 常用国际贸易术语正则库
    private static final Pattern INCOTERM_PATTERN = Pattern.compile(
            "\\b(CIF|FOB|CFR|EXW|DDP|DAP)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // 光伏组件及工业产品型号正则库
    private static final Pattern PRODUCT_PATTERN = Pattern.compile(
            "(\\d{3,4}\\s*W[\\u4e00-\\u9fa5a-zA-Z0-9\\-_\\s]*(?:组件|电池板|光伏|单晶|多晶|双玻|N型|P型|TOPCon|HJT))",
            Pattern.CASE_INSENSITIVE
    );

    // 采购规模正则库
    private static final Pattern VOLUME_PATTERN = Pattern.compile(
            "(\\d+(?:[,，]\\d{3})*\\s*(?:片|套|个|箱|柜|MW|GW|pcs|units|HQ|GP))",
            Pattern.CASE_INSENSITIVE
    );

    // 国家/地区匹配
    private static final Pattern COUNTRY_PATTERN = Pattern.compile(
            "(德国|荷兰|阿联酋|迪拜|沙特|美国|巴西|意大利|西班牙|波兰|法国|英国|日本|韩国|澳大利亚|Germany|Netherlands|UAE|Dubai|Saudi|USA|Brazil|Italy|Spain)",
            Pattern.CASE_INSENSITIVE
    );

    // 价格敏感词
    private static final Pattern PRICE_SENSITIVITY_PATTERN = Pattern.compile(
            "(阶梯折扣|大单优惠|底线单价|最低价|预算敏感|价格敏感|比价|折扣率|target\\s*price)",
            Pattern.CASE_INSENSITIVE
    );

    public EntityMemoryService() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public EntityMemoryService(@Autowired(required = false) CrmBuyerProfileMapper profileMapper, ObjectMapper json) {
        this.profileMapper = profileMapper;
        this.json = json != null ? json : new ObjectMapper();
    }

    /**
     * 获取指定买家的实体画像
     */
    public CrmBuyerProfile getProfile(String tenantId, String buyerId) {
        String safeTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : "1001";
        String safeBuyer = (buyerId != null && !buyerId.isBlank()) ? buyerId.trim() : "anonymous_buyer";
        String cacheKey = safeTenant + ":" + safeBuyer;

        if (profileMapper != null) {
            try {
                CrmBuyerProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<CrmBuyerProfile>()
                        .eq(CrmBuyerProfile::getTenantId, safeTenant)
                        .eq(CrmBuyerProfile::getBuyerId, safeBuyer)
                        .last("LIMIT 1"));
                if (profile != null) {
                    fallbackStore.put(cacheKey, profile);
                    return profile;
                }
            } catch (Exception e) {
                log.warn("查询数据库 crm_buyer_profile 失败，降级读取内存缓存: {}", e.getMessage());
            }
        }
        return fallbackStore.get(cacheKey);
    }

    /**
     * 人工为指定买家的特定字段加锁（被加锁字段 AI 提取时绝对不可篡改）
     */
    @Transactional(rollbackFor = Exception.class)
    public CrmBuyerProfile lockFields(String tenantId, String buyerId, Set<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return getProfile(tenantId, buyerId);
        }
        CrmBuyerProfile profile = getOrCreateProfile(tenantId, buyerId, null);
        Set<String> locked = parseLockedFields(profile.getLockedFieldsJson());
        locked.addAll(fieldNames);
        profile.setLockedFieldsJson(serializeJson(locked));
        profile.setUpdateTime(LocalDateTime.now());
        saveOrUpdate(profile);
        log.info("【实体画像加锁】买家 [{}] 字段已加锁: {}", buyerId, fieldNames);
        return profile;
    }

    /**
     * 人工为指定买家的特定字段解锁
     */
    @Transactional(rollbackFor = Exception.class)
    public CrmBuyerProfile unlockFields(String tenantId, String buyerId, Set<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return getProfile(tenantId, buyerId);
        }
        CrmBuyerProfile profile = getProfile(tenantId, buyerId);
        if (profile == null) return null;

        Set<String> locked = parseLockedFields(profile.getLockedFieldsJson());
        locked.removeAll(fieldNames);
        profile.setLockedFieldsJson(serializeJson(locked));
        profile.setUpdateTime(LocalDateTime.now());
        saveOrUpdate(profile);
        log.info("【实体画像解锁】买家 [{}] 字段已解除锁定: {}", buyerId, fieldNames);
        return profile;
    }

    /**
     * 人工业务员手动修改/核准买家画像（自动将被修改字段加锁）
     */
    @Transactional(rollbackFor = Exception.class)
    public CrmBuyerProfile manualUpdateByHuman(CrmBuyerProfile manualData, boolean autoLockEditedFields) {
        if (manualData == null) return null;
        String tenantId = manualData.getTenantId();
        String buyerId = manualData.getBuyerId();
        CrmBuyerProfile current = getOrCreateProfile(tenantId, buyerId, null);

        Set<String> locked = parseLockedFields(current.getLockedFieldsJson());
        Set<String> modified = new HashSet<>();

        if (manualData.getBuyerName() != null) { current.setBuyerName(manualData.getBuyerName()); modified.add("buyerName"); }
        if (manualData.getCompanyName() != null) { current.setCompanyName(manualData.getCompanyName()); modified.add("companyName"); }
        if (manualData.getCountry() != null) { current.setCountry(manualData.getCountry()); modified.add("country"); }
        if (manualData.getDestinationPort() != null) { current.setDestinationPort(manualData.getDestinationPort()); modified.add("destinationPort"); }
        if (manualData.getPreferredIncoterm() != null) { current.setPreferredIncoterm(manualData.getPreferredIncoterm().toUpperCase()); modified.add("preferredIncoterm"); }
        if (manualData.getTargetProduct() != null) { current.setTargetProduct(manualData.getTargetProduct()); modified.add("targetProduct"); }
        if (manualData.getTargetVolume() != null) { current.setTargetVolume(manualData.getTargetVolume()); modified.add("targetVolume"); }
        if (manualData.getPriceSensitivity() != null) { current.setPriceSensitivity(manualData.getPriceSensitivity()); modified.add("priceSensitivity"); }

        if (autoLockEditedFields) {
            locked.addAll(modified);
            current.setLockedFieldsJson(serializeJson(locked));
        }
        current.setUpdateTime(LocalDateTime.now());
        saveOrUpdate(current);
        return current;
    }

    /**
     * 从文本（单次提问或会话多轮内容）中自动规则提炼买家画像候选信息
     */
    public CrmBuyerProfile extractCandidatesFromText(String text) {
        if (text == null || text.isBlank()) {
            return new CrmBuyerProfile();
        }

        CrmBuyerProfile candidate = new CrmBuyerProfile();

        // 1. 目的港提取 (优先匹配知名核心外贸港口词库，保底走正则)
        for (String p : COMMON_WORLD_PORTS) {
            if (text.contains(p)) {
                candidate.setDestinationPort(p);
                break;
            }
        }
        if (candidate.getDestinationPort() == null || candidate.getDestinationPort().isBlank()) {
            Matcher portMatcher = PORT_PATTERN.matcher(text);
            if (portMatcher.find()) {
                String port = portMatcher.group(1) != null ? portMatcher.group(1).trim() : (portMatcher.group(2) != null ? portMatcher.group(2).trim() : "");
                port = port.replaceAll("^(?i)(?:CIF|FOB|CFR|EXW|DDP|DAP)\\s*", "").trim();
                if (port.length() >= 2 && port.length() <= 30) {
                    candidate.setDestinationPort(port);
                }
            }
        }

        // 2. 贸易术语提取
        Matcher incotermMatcher = INCOTERM_PATTERN.matcher(text);
        if (incotermMatcher.find()) {
            candidate.setPreferredIncoterm(incotermMatcher.group(1).toUpperCase());
        }

        // 3. 意向采购产品提取
        Matcher productMatcher = PRODUCT_PATTERN.matcher(text);
        if (productMatcher.find()) {
            candidate.setTargetProduct(productMatcher.group(1).trim());
        }

        // 4. 意向规模提取
        Matcher volumeMatcher = VOLUME_PATTERN.matcher(text);
        if (volumeMatcher.find()) {
            candidate.setTargetVolume(volumeMatcher.group(1).replaceAll("\\s+", "").trim());
        }

        // 5. 目标国家提取
        Matcher countryMatcher = COUNTRY_PATTERN.matcher(text);
        if (countryMatcher.find()) {
            candidate.setCountry(countryMatcher.group(1).trim());
        }

        // 6. 价格敏感度提取
        Matcher priceMatcher = PRICE_SENSITIVITY_PATTERN.matcher(text);
        if (priceMatcher.find()) {
            candidate.setPriceSensitivity("HIGH");
        }

        return candidate;
    }

    /**
     * 【核心融合算法】严格执行「只填空不覆盖」与「人工加锁保护」原则
     *
     * @return 实际被新填充修改的字段列表
     */
    @Transactional(rollbackFor = Exception.class)
    public MergeResult mergeExtractedProfile(String tenantId, String buyerId, CrmBuyerProfile candidate, String sessionId) {
        if (candidate == null) {
            return new MergeResult(getProfile(tenantId, buyerId), Collections.emptyList(), Collections.emptyList());
        }

        CrmBuyerProfile profile = getOrCreateProfile(tenantId, buyerId, sessionId);
        Set<String> locked = parseLockedFields(profile.getLockedFieldsJson());

        List<String> filledFields = new ArrayList<>();
        List<String> skippedFields = new ArrayList<>();

        // 检查并融合每个字段
        checkAndFill("country", candidate.getCountry(), profile.getCountry(), locked, filledFields, skippedFields, profile::setCountry);
        checkAndFill("destinationPort", candidate.getDestinationPort(), profile.getDestinationPort(), locked, filledFields, skippedFields, profile::setDestinationPort);
        checkAndFill("preferredIncoterm", candidate.getPreferredIncoterm(), profile.getPreferredIncoterm(), locked, filledFields, skippedFields, profile::setPreferredIncoterm);
        checkAndFill("targetProduct", candidate.getTargetProduct(), profile.getTargetProduct(), locked, filledFields, skippedFields, profile::setTargetProduct);
        checkAndFill("targetVolume", candidate.getTargetVolume(), profile.getTargetVolume(), locked, filledFields, skippedFields, profile::setTargetVolume);
        checkAndFill("priceSensitivity", candidate.getPriceSensitivity(), profile.getPriceSensitivity(), locked, filledFields, skippedFields, profile::setPriceSensitivity);
        checkAndFill("buyerName", candidate.getBuyerName(), profile.getBuyerName(), locked, filledFields, skippedFields, profile::setBuyerName);
        checkAndFill("companyName", candidate.getCompanyName(), profile.getCompanyName(), locked, filledFields, skippedFields, profile::setCompanyName);

        if (!filledFields.isEmpty()) {
            if (sessionId != null && !sessionId.isBlank()) {
                profile.setSourceSessionId(sessionId);
            }
            profile.setUpdateTime(LocalDateTime.now());
            saveOrUpdate(profile);
            log.info("【实体画像只填空成功】买家 [{}] 新填充字段: {}, 保持/跳过字段: {}", buyerId, filledFields, skippedFields);
        }

        return new MergeResult(profile, filledFields, skippedFields);
    }

    public record MergeResult(
            CrmBuyerProfile profile,
            List<String> filledFields,
            List<String> skippedFields
    ) {}

    private void checkAndFill(String fieldName, String incomingVal, String currentVal,
                              Set<String> locked, List<String> filled, List<String> skipped,
                              java.util.function.Consumer<String> setter) {
        if (incomingVal == null || incomingVal.isBlank()) {
            return;
        }

        // 1. 若字段被人为加锁，AI 严禁覆盖
        if (locked.contains(fieldName)) {
            skipped.add(fieldName + "(LOCKED)");
            log.debug("买家实体画像字段 [{}] 处于人工加锁状态，跳过 AI 提取填充", fieldName);
            return;
        }

        // 2. 若字段已有非空值，按「只填空不覆盖」原则跳过
        if (currentVal != null && !currentVal.isBlank()) {
            skipped.add(fieldName + "(ALREADY_EXISTS)");
            log.debug("买家实体画像字段 [{}] 已存在值 '{}'，按只填空原则保留原值", fieldName, currentVal);
            return;
        }

        // 3. 字段为 NULL 或空白，执行填空
        setter.accept(incomingVal.trim());
        filled.add(fieldName);
    }

    /**
     * 将买家实体画像格式化为不可篡改的 Prompt 上下文（可注入 ZONE 2 或 ZONE 1）
     */
    public String formatAsPromptContext(CrmBuyerProfile profile) {
        if (profile == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【买家实体画像持久记忆 (Entity Memory - 跨会话一致认知)】\n");
        if (profile.getBuyerName() != null || profile.getCompanyName() != null) {
            sb.append("- 客户企业: ").append(profile.getCompanyName() != null ? profile.getCompanyName() : "未知")
                    .append(" (联系人: ").append(profile.getBuyerName() != null ? profile.getBuyerName() : "买家").append(")\n");
        }
        if (profile.getCountry() != null) {
            sb.append("- 目标国别: ").append(profile.getCountry()).append("\n");
        }
        if (profile.getDestinationPort() != null) {
            sb.append("- 习惯指定目的港: ").append(profile.getDestinationPort());
            if (isFieldLocked(profile, "destinationPort")) sb.append(" [人工已核准锁定]");
            sb.append("\n");
        }
        if (profile.getPreferredIncoterm() != null) {
            sb.append("- 常用国际贸易术语: ").append(profile.getPreferredIncoterm());
            if (isFieldLocked(profile, "preferredIncoterm")) sb.append(" [人工已核准锁定]");
            sb.append("\n");
        }
        if (profile.getTargetProduct() != null) {
            sb.append("- 核心关注产品型号: ").append(profile.getTargetProduct()).append("\n");
        }
        if (profile.getTargetVolume() != null) {
            sb.append("- 常规采购规模/柜型: ").append(profile.getTargetVolume()).append("\n");
        }
        if (profile.getPriceSensitivity() != null) {
            sb.append("- 价格敏感策略: ").append(profile.getPriceSensitivity()).append("\n");
        }
        return sb.toString().trim();
    }

    public boolean isFieldLocked(CrmBuyerProfile profile, String fieldName) {
        if (profile == null || fieldName == null) return false;
        return parseLockedFields(profile.getLockedFieldsJson()).contains(fieldName);
    }

    private CrmBuyerProfile getOrCreateProfile(String tenantId, String buyerId, String sessionId) {
        CrmBuyerProfile profile = getProfile(tenantId, buyerId);
        if (profile != null) return profile;

        String safeTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : "1001";
        String safeBuyer = (buyerId != null && !buyerId.isBlank()) ? buyerId.trim() : "buyer_" + System.currentTimeMillis();
        profile = CrmBuyerProfile.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .tenantId(safeTenant)
                .buyerId(safeBuyer)
                .lockedFieldsJson("[]")
                .extraAttributesJson("{}")
                .sourceSessionId(sessionId)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .deleted(0)
                .build();
        return profile;
    }

    private void saveOrUpdate(CrmBuyerProfile profile) {
        String cacheKey = profile.getTenantId() + ":" + profile.getBuyerId();
        fallbackStore.put(cacheKey, profile);

        if (profileMapper != null) {
            try {
                CrmBuyerProfile exist = profileMapper.selectById(profile.getId());
                if (exist == null) {
                    profileMapper.insert(profile);
                } else {
                    profileMapper.updateById(profile);
                }
            } catch (Exception e) {
                log.warn("持久化 crm_buyer_profile 失败，数据已保存在内存回退层: {}", e.getMessage());
            }
        }
    }

    private Set<String> parseLockedFields(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return new HashSet<>();
        try {
            List<String> list = json.readValue(jsonStr, new TypeReference<List<String>>() {});
            return new HashSet<>(list);
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private String serializeJson(Object obj) {
        try {
            return json.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
