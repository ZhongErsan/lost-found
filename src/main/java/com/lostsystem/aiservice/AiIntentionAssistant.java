package com.lostsystem.aiservice;

import com.lostsystem.aioutput.IntentionOutput;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * AI意图识别助手接口
 * <p>
 * 基于Spring AI AiService声明式接口，负责解析用户输入消息，识别用户意图
 * 采用显式装配模式，指定模型、对话记忆提供者、工具集，绑定会话维度记忆
 * </p>
 *
 * @author xxx
 */
@AiService(
        // 装配模式：EXPLICIT 显式模式，不自动扫描注入，所有依赖组件手动指定Bean名称
        wiringMode = AiServiceWiringMode.EXPLICIT,
        // 指定对话模型Bean名称：使用openAiChatModel
        chatModel = "openAiChatModel",
        // 指定对话记忆提供者Bean：intentionChatMemoryProvider，按会话隔离记忆
        chatMemoryProvider = "intentionChatMemoryProvider",
        // 注入可用工具Bean：chatHistoryTools，提供对话历史查询等工具能力
        tools = "chatHistoryTools"
)
public interface AiIntentionAssistant {

    /**
     * 系统提示词配置：从classpath资源文件加载系统prompt
     * 资源路径：/getIntention.txt，文件内定义意图识别规则、输出格式约束、角色设定
     */
    @SystemMessage(fromResource = "/getIntention.txt")
    /**
     * 用户消息模板，模板变量由方法入参绑定
     * sessionId：会话唯一标识，用于隔离不同用户对话上下文
     * message：用户本轮输入原始消息
     */
    @UserMessage("当前sessionId:{{sessionId}};用户当前消息：{{message}}")
    /**
     * 识别用户意图
     * @param sessionId 会话ID，@MemoryId 标记为对话记忆的分组主键，同一个sessionId共享记忆上下文
     * @param message   用户当前输入消息
     * @return IntentionOutput 意图识别结果封装对象，包含意图类型、置信度、提取参数等字段
     */
    IntentionOutput intention(
            @MemoryId @V("sessionId") String sessionId,
            @V("message") String message
    );
}

