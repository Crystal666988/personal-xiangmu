package com.tradeagent.modules.outreach;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外贸开发信反垃圾邮件敏感词过滤器 (Spam Word Filter)
 * 
 * 核心技术考点 (简历第 95 行)：
 * 海外企业邮箱 (如 Gmail / Outlook) 对敏感诱导词极度严格。
 * 在邮件真正进入投递计划前，静态拦截违禁词，防止 IP 信誉度受损被加入反垃圾联盟黑名单。
 */
@Slf4j
@Component
public class SpamWordFilter {

    // 常见外贸垃圾邮件高危触发词库 (不区分大小写)
    private static final Pattern SPAM_PATTERN = Pattern.compile(
            "(?i)(100% free|risk free|guaranteed money|wire transfer|urgent reply|make money fast|casino|viagra|纯免费|保证暴富|速汇)"
    );

    /**
     * 校验邮件内容是否包含垃圾邮件高危词
     *
     * @param htmlContent 待发送的 HTML 邮件内容
     * @return 命中的敏感词列表 (空代表检测通过)
     */
    public List<String> checkSpamWords(String htmlContent) {
        if (htmlContent == null || htmlContent.isBlank()) {
            return Collections.emptyList();
        }

        List<String> detectedWords = new ArrayList<>();
        Matcher matcher = SPAM_PATTERN.matcher(htmlContent);
        while (matcher.find()) {
            detectedWords.add(matcher.group());
        }

        if (!detectedWords.isEmpty()) {
            log.warn("【反垃圾邮件防火墙】拦截到高危违规词: {}", detectedWords);
        }
        return detectedWords;
    }
}
