package com.example.demo.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatClient configuration for the different LLM providers.
 * <p>
 * - analysisChatClient (GPT-5.1): used by analysis and verification agents
 * - reportChatClient (Haiku 4.5): used for LaTeX report generation
 */
@Configuration
public class AiConfig {

    /**
     * ChatClient for document analysis (GPT-5.1 via OpenAI).
     * Used by RequirementsAndUseCaseAgent, TestAuditorAgent, and ConsistencyManager.
     */
    @Bean("analysisChatClient")
    public ChatClient analysisChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
    }

    /**
     * ChatClient for LaTeX report generation (Haiku 4.5 via Anthropic).
     */
    @Bean("reportChatClient")
    public ChatClient reportChatClient(AnthropicChatModel anthropicChatModel) {
        return ChatClient.builder(anthropicChatModel).build();
    }

    /**
     * Executor with virtual threads for parallel agent execution.
     */
    @Bean
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Shared ObjectMapper for JSON serialization.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
