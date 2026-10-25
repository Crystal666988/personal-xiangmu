package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OTA 固件灰度发布：基于设备影子的"拉式升级"。
 *
 * 流程：上传固件 → 选定灰度批次设备 → 把 {fwVersion, fwUrl, fwMd5} 写入这些设备影子的
 * desired → 设备上线时从 pending 拿到升级指令 → 下载校验 MD5 → 刷写 → 上报 fwVersion →
 * reported 追平 desired，灰度进度通过影子比对统计。失败回滚 = 设备保留旧版本并上报，
 * 平台检测 reported 与 desired 版本长期不一致即告警人工介入（不自动全量推进）。
 */
@Slf4j
@Service
public class IotOtaService {

    private final IotFirmwareMapper firmwareMapper;
    private final IotDeviceService deviceService;
    private final IotShadowService shadowService;

    public IotOtaService(IotFirmwareMapper firmwareMapper, IotDeviceService deviceService,
                         IotShadowService shadowService) {
        this.firmwareMapper = firmwareMapper;
        this.deviceService = deviceService;
        this.shadowService = shadowService;
    }

    public IotFirmware create(String id, String version, String url, Long sizeBytes, String md5, int greyBatch) {
        if (id == null || id.isBlank() || version == null || version.isBlank() || url == null || url.isBlank()) {
            throw new IllegalArgumentException("固件 ID / 版本 / 下载地址均为必填项");
        }
        IotFirmware fw = IotFirmware.builder()
                .id(id.trim()).version(version.trim()).url(url.trim())
                .sizeBytes(sizeBytes).md5(md5).greyBatch(Math.max(1, greyBatch))
                .state("CREATED").createTime(java.time.LocalDateTime.now())
                .build();
        firmwareMapper.insert(fw);
        return fw;
    }

    public List<IotFirmware> list() {
        return firmwareMapper.selectList(new LambdaQueryWrapper<IotFirmware>()
                .orderByDesc(IotFirmware::getCreateTime));
    }

    /** 灰度推送：把固件期望写入前 N 台设备（旧版本优先）的影子 desired。 */
    public int rollout(String tenantId, String firmwareId) {
        IotFirmware fw = firmwareMapper.selectById(firmwareId);
        if (fw == null) throw new IllegalArgumentException("固件不存在");
        List<IotDevice> devices = deviceService.list(tenantId, null);
        List<IotDevice> targets = devices.stream()
                .filter(d -> fw.getVersion() == null || !fw.getVersion().equals(d.getFirmwareVersion()))
                .limit(fw.getGreyBatch() == null ? 10 : fw.getGreyBatch())
                .toList();
        String cmd = String.format(
                "{\"command\":\"ota_upgrade\",\"fwVersion\":\"%s\",\"fwUrl\":\"%s\",\"fwMd5\":\"%s\"}",
                fw.getVersion(), fw.getUrl(), fw.getMd5() == null ? "" : fw.getMd5());
        targets.forEach(d -> shadowService.mergeDesired(tenantId, d.getDeviceId(), cmd));
        firmwareMapper.updateById(IotFirmware.builder().id(fw.getId()).state("ROLLING").build());
        log.info("【OTA】固件 {} v{} 灰度推送 {} 台设备 (经影子 desired)", firmwareId, fw.getVersion(), targets.size());
        return targets.size();
    }

    /** 灰度进度：desired 版本 vs 设备台账 fwVersion 的追平率。 */
    public String progress(String tenantId, String firmwareId) {
        IotFirmware fw = firmwareMapper.selectById(firmwareId);
        if (fw == null) throw new IllegalArgumentException("固件不存在");
        List<IotDevice> devices = deviceService.list(tenantId, null);
        long upgraded = devices.stream()
                .filter(d -> fw.getVersion().equals(d.getFirmwareVersion()))
                .count();
        return String.format("v%s: %d/%d 台已升级 (%.0f%%)", fw.getVersion(), upgraded, devices.size(),
                devices.isEmpty() ? 0 : upgraded * 100.0 / devices.size());
    }
}
