package com.tradeagent.modules.iot;

/**
 * 设备数据事件发布器抽象：入库后把数据点转发给下游（离线分析/大屏/告警消费者）。
 *
 * 默认实现为进程内日志（演示环境无 Kafka）；生产部署打开 iot.kafka.enabled=true
 * 后由 Kafka 生产者实现接管，消费侧（报警、时序聚合）无感切换。
 */
public interface IotDeviceEventPublisher {
    void publish(IotDeviceData data);
}
