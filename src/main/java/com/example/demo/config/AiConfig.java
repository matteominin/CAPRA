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
 * Configurazione dei ChatClient per i diversi provider LLM.
 * <p>
 * - analysisChatClient (GPT-5.1): usato dagli agenti di analisi e verifica
 * - reportChatClient (Haiku 4.5): usato per la generazione del report LaTeX
 */
@Configuration
public class AiConfig {

    /**
     * ChatClient per l'analisi del documento (GPT-5.1 via OpenAI).
     * Usato da RequirementsAgent, TestAuditorAgent e ConsistencyManager.
     */
    @Bean("analysisChatClient")
    public ChatClient analysisChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
    }

    /**
     * ChatClient per la generazione del report LaTeX (Haiku 4.5 via Anthropic).
     */
    @Bean("reportChatClient")
    public ChatClient reportChatClient(AnthropicChatModel anthropicChatModel) {
        return ChatClient.builder(anthropicChatModel).build();
    }

    /**
     * Executor con thread virtuali per l'esecuzione parallela degli agenti.
     */
    @Bean
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * ObjectMapper condiviso per la serializzazione JSON.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
