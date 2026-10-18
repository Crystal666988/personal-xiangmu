import test from 'node:test'
import assert from 'node:assert/strict'
import { buildRecentHistory } from './chatMemory.js'

test('回复被删除后，窗口仍按用户提问计算轮次', () => {
  const messages = [
    { role: 'user', content: '旧问题' },
    { role: 'assistant', content: '旧回复' },
    { role: 'user', content: '回复已删除的问题' },
    { role: 'user', content: '最新问题' },
    { role: 'assistant', content: '最新回复', thought: '不应进入历史' },
  ]
  assert.deepEqual(buildRecentHistory(messages, 2), messages.slice(2).map(({ role, content }) => ({ role, content })))
})

test('保留只有附件的提问，限制附件及回复长度且不修改原数据', () => {
  const messages = [
    { role: 'user', content: '', attachment: { extractedText: '文'.repeat(900) } },
    { role: 'assistant', content: '答'.repeat(3000) },
  ]
  const result = buildRecentHistory(messages)
  assert.equal(result.length, 2)
  assert.ok(result[0].content.includes('文'.repeat(800) + '…'))
  assert.ok(result[1].content.includes('[省略中间冗余]'))
  assert.equal(messages[1].content.length, 3000)
})

test('忽略孤立回复与系统消息，正确处理空历史及无效窗口', () => {
  assert.deepEqual(buildRecentHistory([{ role: 'assistant', content: '孤立回复' }, { role: 'system', content: '配置' }]), [])
  assert.deepEqual(buildRecentHistory([]), [])
  assert.deepEqual(buildRecentHistory([{ role: 'user', content: '问题' }], 0), [])
})
