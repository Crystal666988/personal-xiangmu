package com.tradeagent.modules.prospect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 客源线索实体 (带全渠道查重指纹)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProspectLead implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 企业名称 */
    private String companyName;

    /** 所在国家与城市 */
    private String country;

    /** 官网地址 */
    private String website;

    /** 核心联系电话 / WhatsApp */
    private String phone;

    /** 商务联系邮箱 */
    private String email;

    /** 来源渠道: GOOGLE_MAPS, FACEBOOK, LINKEDIN, WHATSAPP */
    private String sourceChannel;

    /** 物理地址 / 地理坐标 */
    private String address;

    /** 查重指纹 (根据 规范化域名 / 国际电话 生成唯一哈希，杜绝重复落库) */
    public String generateDeduplicationFingerprint() {
        String cleanDomain = website != null ? website.replaceAll("https?://|www\\.|/.*", "").toLowerCase() : "";
        String cleanPhone = phone != null ? phone.replaceAll("[^0-9]", "") : "";
        return String.format("%s_%s_%s", cleanDomain, cleanPhone, companyName != null ? companyName.trim().toLowerCase() : "");
    }
}
