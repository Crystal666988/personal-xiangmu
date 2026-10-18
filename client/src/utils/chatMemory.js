// 按用户提问划分轮次，避免删除过回复后按固定消息条数切出半轮对话。
export function buildRecentHistory(messages, maxTurns = 4) {
  if (!Number.isInteger(maxTurns) || maxTurns < 1) return []
  const turns = []
  for (const message of messages) {
    if (message.role === 'user') turns.push([])
    if (!turns.length || !['user', 'assistant'].includes(message.role)) continue

    let content = message.content || ''
    if (message.role === 'user' && message.attachment?.extractedText) {
      const text = message.attachment.extractedText
      const excerpt = text.slice(0, 800) + (text.length > 800 ? '…' : '')
      content = `【历史附件《${message.attachment.name || '文档/图片'}》已提取要点】:\n${excerpt}\n\n用户提问: ${content}`
    } else if (message.role === 'assistant' && content.length > 2000) {
      content = content.slice(0, 1500) + '\n...[省略中间冗余]...\n' + content.slice(-500)
    }
    // 仅含附件的用户消息也应保留；不将 thought 等展示字段发给模型。
    if (content.trim()) turns.at(-1).push({ role: message.role, content })
  }
  return turns.slice(-maxTurns).flat()
}
