package com.example.agentdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 绑定 application.yml 的 llm.chat.*（本作品只用 DeepSeek 的 chat，不需要 embedding） */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private Chat chat = new Chat();

    public Chat getChat() { return chat; }
    public void setChat(Chat chat) { this.chat = chat; }

    public static class Chat {
        private String baseUrl;
        private String model;
        private String apiKey;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}
