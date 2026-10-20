package com.tradeagent.modules.ai.rag.benchmark;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 基准评测持久化记录 Mapper
 */
@Mapper
public interface RagBenchmarkRecordMapper extends BaseMapper<RagBenchmarkRecord> {
}
