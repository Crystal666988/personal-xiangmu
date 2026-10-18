package com.tradeagent.modules.ai.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 智能体会话与历史消息数据库业务层
 */
@Slf4j
@Service
public class AgentSessionService {

    @Resource
    private AgentSessionMapper sessionMapper;

    @Resource
    private AgentMessageMapper messageMapper;

    /**
     * 查询指定租户的全部会话列表 (按更新时间倒序)
     */
    public List<AgentSession> listSessions(String tenantId) {
        String safeTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "1001";
        return sessionMapper.selectList(new LambdaQueryWrapper<AgentSession>()
                .eq(AgentSession::getTenantId, safeTenant)
                .orderByDesc(AgentSession::getUpdateTime));
    }

    /**
     * 保存或创建会话
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentSession saveOrCreateSession(AgentSession session) {
        if (session.getId() == null || session.getId().isBlank()) {
            session.setId("session_" + System.currentTimeMillis());
        }
        if (session.getTenantId() == null || session.getTenantId().isBlank()) {
            session.setTenantId("1001");
        }
        if (session.getTitle() == null || session.getTitle().isBlank()) {
            session.setTitle("新会话");
        }
        if (session.getAgentId() == null || session.getAgentId().isBlank()) {
            session.setAgentId("commerce-agent");
        }
        session.setCreateTime(LocalDateTime.now());
        session.setUpdateTime(LocalDateTime.now());

        AgentSession exist = sessionMapper.selectById(session.getId());
        if (exist == null) {
            sessionMapper.insert(session);
        } else {
            sessionMapper.updateById(session);
        }
        return session;
    }

    /**
     * 更新会话标题与活跃时间
     */
    public void updateSessionTitle(String sessionId, String title) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setTitle(title);
            session.setUpdateTime(LocalDateTime.now());
            sessionMapper.updateById(session);
        }
    }

    /**
     * 删除会话及其关联消息
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(String sessionId) {
        sessionMapper.deleteById(sessionId);
        messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId));
        log.info("【会话管理】已成功从 MySQL 删除会话及全部历史消息: {}", sessionId);
    }

    /**
     * 查询指定会话的全部历史消息 (按时间正序)
     */
    public List<AgentMessage> listMessages(String sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId)
                .orderByAsc(AgentMessage::getCreateTime)
                .orderByAsc(AgentMessage::getId));
    }

    /**
     * 新增一条对话消息并刷新会话 updateTime
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentMessage saveMessage(AgentMessage message) {
        if (message.getId() == null || message.getId().isBlank()) {
            message.setId("msg_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000));
        }
        message.setCreateTime(LocalDateTime.now());
        messageMapper.insert(message);

        // 级联更新会话更新时间
        AgentSession session = sessionMapper.selectById(message.getSessionId());
        if (session != null) {
            session.setUpdateTime(LocalDateTime.now());
            sessionMapper.updateById(session);
        }

        log.debug("【消息落库】消息已存入 MySQL: id={}, session={}, role={}", message.getId(), message.getSessionId(), message.getRole());
        return message;
    }

    /**
     * 清空指定会话的全部消息
     */
    public void clearMessages(String sessionId) {
        messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId));
    }

    /**
     * 删除单条消息
     */
    public void deleteMessage(String sessionId, String messageId) {
        messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId)
                .eq(AgentMessage::getId, messageId));
        log.info("【消息管理】已从 MySQL 删除消息: sessionId={}, msgId={}", sessionId, messageId);
    }

    /**
     * 批量删除消息 (用于删除一整轮问答对话)
     */
    public void deleteMessages(String sessionId, List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return;
        messageMapper.delete(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getSessionId, sessionId)
                .in(AgentMessage::getId, messageIds));
        log.info("【消息管理】已从 MySQL 批量删除问答轮次消息: sessionId={}, msgIds={}", sessionId, messageIds);
    }
}
