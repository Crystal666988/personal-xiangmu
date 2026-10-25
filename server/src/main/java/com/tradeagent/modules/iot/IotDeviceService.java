package com.tradeagent.modules.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备台账 CRUD + 入库校验：只有注册过的设备才允许上报（杜绝脏设备写穿存储）。
 */
@Slf4j
@Service
public class IotDeviceService {

    private final IotDeviceMapper deviceMapper;

    public IotDeviceService(IotDeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    public record DeviceUpsert(String deviceId, String farmId, String name, String type,
                               String productId, String location, String secret,
                               Double tempLow, Double tempHigh, Double humidLow, Double humidHigh) {}

    public List<IotDevice> list(String tenantId, String keyword) {
        LambdaQueryWrapper<IotDevice> wrapper = new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getTenantId, tenantId)
                .orderByDesc(IotDevice::getLastSeen);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(IotDevice::getName, keyword)
                    .or().like(IotDevice::getDeviceId, keyword)
                    .or().like(IotDevice::getFarmId, keyword));
        }
        return deviceMapper.selectList(wrapper);
    }

    public IotDevice create(String tenantId, DeviceUpsert req) {
        validate(req);
        if (deviceMapper.selectById(req.deviceId()) != null) {
            throw new IllegalArgumentException("设备 ID 已存在: " + req.deviceId());
        }
        IotDevice device = IotDevice.builder()
                .deviceId(req.deviceId().trim())
                .tenantId(tenantId)
                .farmId(req.farmId().trim())
                .productId(req.productId())
                .location(req.location())
                .secret(req.secret())
                .name(req.name().trim())
                .type(req.type() == null || req.type().isBlank() ? "ENV" : req.type().trim())
                .tempLow(req.tempLow()).tempHigh(req.tempHigh())
                .humidLow(req.humidLow()).humidHigh(req.humidHigh())
                .createTime(LocalDateTime.now())
                .build();
        deviceMapper.insert(device);
        return device;
    }

    public IotDevice update(String tenantId, String deviceId, DeviceUpsert req) {
        validate(req);
        IotDevice device = owned(tenantId, deviceId);
        device.setFarmId(req.farmId().trim());
        device.setProductId(req.productId());
        device.setLocation(req.location());
        if (req.secret() != null) device.setSecret(req.secret().isBlank() ? null : req.secret());
        device.setName(req.name().trim());
        if (req.type() != null && !req.type().isBlank()) device.setType(req.type().trim());
        device.setTempLow(req.tempLow());
        device.setTempHigh(req.tempHigh());
        device.setHumidLow(req.humidLow());
        device.setHumidHigh(req.humidHigh());
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
        return device;
    }

    /** 删除设备：历史数据保留（分区表），仅台账下线。 */
    public boolean delete(String tenantId, String deviceId) {
        return deviceMapper.delete(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getTenantId, tenantId)
                .eq(IotDevice::getDeviceId, deviceId)) > 0;
    }

    public long countByProduct(String tenantId, String productId) {
        return deviceMapper.selectCount(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getTenantId, tenantId)
                .eq(IotDevice::getProductId, productId));
    }

    public IotDevice require(String tenantId, String deviceId) {
        return owned(tenantId, deviceId);
    }

    private IotDevice owned(String tenantId, String deviceId) {
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null || !tenantId.equals(device.getTenantId())) {
            throw new IllegalArgumentException("设备未注册或不属于当前租户: " + deviceId);
        }
        return device;
    }

    private void validate(DeviceUpsert req) {
        if (req == null || req.deviceId() == null || req.deviceId().isBlank()
                || req.farmId() == null || req.farmId().isBlank()
                || req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("设备 ID / 栋舍 / 名称均为必填项");
        }
    }
}
