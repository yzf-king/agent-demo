package com.example.agentdemo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek（OpenAI 兼容协议）的手动装配，和 rag-demo 的 chat 部分一致。
 *
 * 为什么不用自动配置：自动配置只认 spring.ai.openai.*（一家），而 rag-demo 要同时接
 * DeepSeek + DashScope 两家；这里沿用同样的写法，保持两个作品配置风格一致。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class ChatConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatConfig.class);

    @Bean
    public OpenAiChatModel chatModel(LlmProperties props) {
        String key = props.getChat().getApiKey();
        // 只打印"读到没有 + 长度"，绝不打印 key 本身
        log.info("chat(DeepSeek) API key: {}（长度 {}）",
                (key != null && !key.isBlank()) ? "已读到" : "【为空！】",
                (key != null) ? key.length() : 0);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.getChat().getBaseUrl())
                .apiKey(key)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.getChat().getModel())
                        .build())
                .build();
    }

    /**
     * 两个 Agent 都靠 ChatClient 发请求：手写版用它拿纯文本（自己解析 Thought/Action），
     * 框架版用它跑原生 tool calling（.toolCallbacks(...)）。
     */
    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
