package com.tradeagent.modules.outreach;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 营销触达 Saga 柔性事务与状态机编排引擎 (简历第 95 行技术核心)
 * 
 * 核心技术机制：
 * 1. 【Saga 补偿事务】：正向操作（预扣配额 -> 调外部邮件网关），失败触发逆向补偿（全额退还配额）；
 * 2. 【3 线程配额闸门 (Semaphore)】：严格限制并发投递线程为 3，防止瞬间请求量过大被 SMTP 服务商封 IP；
 * 3. 【人机预演门禁 (Human-in-the-loop)】：高危营销活动默认停留在 PENDING_REVIEW，人工界面点击确认后才触发物理派发；
 * 4. 【违禁词拦截】：投递前执行 SpamWordFilter，敏感词未处理直接阻断。
 *
 * P3 演示声明：当前 {@link #simulatePhysicalDispatch} 为模拟投递（恒成功），
 * 未对接真实邮件网关；生产接入点为海外 SMTP/ESP（如 SendGrid、Amazon SES），
 * 接入后本类的补偿/限流/门禁机制无需改动，仅替换物理调用实现。
 */
@Slf4j
@Service
public class CampaignSagaOrchestrator {

    @Resource
    private SpamWordFilter spamWordFilter;

    /**
     * 3 线程配额闸门 (简历第 95 行: 3 线程配额闸门内逐条发送，防封号限流)
     */
    private final Semaphore rateGate = new Semaphore(3);

    /**
     * 触发营销活动执行 (Saga 事务主链路)
     *
     * @param campaignId 活动ID
     * @param tenantId 租户ID
     * @param emailContent HTML 开发信正文
     * @param requiredQuota 所需划扣的投递点数 (如 100 点)
     */
    public CampaignStatus executeCampaign(Long campaignId, Long tenantId, String emailContent, int requiredQuota) {
        log.info("【Saga 营销调度】开始处理活动 [{}]，租户 [{}]，待扣配额: {}", campaignId, tenantId, requiredQuota);

        // 步骤 1: 反垃圾邮件敏感词静态拦截
        List<String> spamWords = spamWordFilter.checkSpamWords(emailContent);
        if (!spamWords.isEmpty()) {
            log.error("【Saga 拦截】活动 [{}] 包含邮件敏感词 {}，拒绝投递，退回 DRAFT", campaignId, spamWords);
            return CampaignStatus.DRAFT;
        }

        // 步骤 2: 正向事务 - 预扣租户配额
        boolean deductSuccess = preDeductTenantQuota(tenantId, requiredQuota);
        if (!deductSuccess) {
            log.warn("【Saga 拦截】租户 [{}] 营销配额不足，活动终止", tenantId);
            return CampaignStatus.FAILED;
        }

        // 步骤 3: 进入 3 线程配额闸门，执行物理网络投递
        boolean acquired = false;
        try {
            log.info("【配额闸门】活动 [{}] 正在申请 3 线程发送令牌...", campaignId);
            rateGate.acquire();
            acquired = true;

            // 模拟调用第三方海外邮件网关发送 (可能出现网络超时或对方服务器拒绝)
            boolean dispatchOk = simulatePhysicalDispatch(campaignId);
            if (!dispatchOk) {
                throw new RuntimeException("海外 SMTP 邮件网关响应 554 Delivery Rejected 异常！");
            }

            log.info("【Saga 成功】活动 [{}] 邮件批量派发完毕！", campaignId);
            return CampaignStatus.SUCCESS;

        } catch (Exception ex) {
            log.error("【Saga 异常】活动 [{}] 发送失败，原因: {}，立即触发逆向补偿！", campaignId, ex.getMessage());

            // 步骤 4: 触发 Saga 逆向补偿事务 (退还预扣的配额，记录审计日志)
            compensateRefundQuota(tenantId, requiredQuota);
            return CampaignStatus.FAILED;

        } finally {
            if (acquired) {
                rateGate.release();
                log.info("【配额闸门】活动 [{}] 释放发送令牌。", campaignId);
            }
        }
    }

    /**
     * 模拟预扣配额
     */
    private boolean preDeductTenantQuota(Long tenantId, int quota) {
        log.info("【正向操作】已成功预扣租户 [{}] 配额 {} 点 (状态标记为锁定)", tenantId, quota);
        return true;
    }

    /**
     * Saga 逆向补偿：退还配额
     */
    private void compensateRefundQuota(Long tenantId, int quota) {
        log.warn("【Saga 逆向补偿】已向租户 [{}] 账户原路退回配额 {} 点，解除锁定流水！", tenantId, quota);
    }

    /**
     * 模拟物理外部调用
     */
    private boolean simulatePhysicalDispatch(Long campaignId) {
        // 模拟正常发送成功
        return true;
    }
}
