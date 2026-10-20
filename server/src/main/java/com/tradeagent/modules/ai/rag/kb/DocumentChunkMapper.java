package com.tradeagent.modules.ai.rag.kb;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库切片持久化数据访问接口
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {
}
