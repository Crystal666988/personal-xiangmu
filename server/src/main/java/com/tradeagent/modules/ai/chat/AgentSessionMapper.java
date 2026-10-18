package com.tradeagent.modules.ai.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话主表 Mapper
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
}
