package com.tradeagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * TradeAgent 外贸 AI 智能体获客与多 Agent 协同平台启动类
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication(exclude = {
    org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration.class
})
public class TradeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeAgentApplication.class, args);
        System.out.println("==========================================================");
        System.out.println(" TradeAgent 核心调度引擎启动成功! 访问接口文档: http://localhost:48050/doc.html");
        System.out.println("==========================================================");
    }
}
