package com.tradeagent.modules.ai.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息明细表 Mapper
 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {
}
