-- routing 元数据升为 MEDIUMTEXT：完整保存工具响应（含 RAG 切片全文），不再截断
ALTER TABLE agent_message MODIFY COLUMN routing MEDIUMTEXT NULL;
