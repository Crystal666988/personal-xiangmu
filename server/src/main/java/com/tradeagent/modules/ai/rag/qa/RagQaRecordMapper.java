package com.tradeagent.modules.ai.rag.qa;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 在线问答历史记录 Mapper
 */
@Mapper
public interface RagQaRecordMapper extends BaseMapper<RagQaRecord> {
}
