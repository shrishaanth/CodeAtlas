package io.github.shrishaanth.codeatlas.config;

import io.github.shrishaanth.codeatlas.qa.LlmClient;
import io.github.shrishaanth.codeatlas.qa.OpenAiCompatibleClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Creates the language-model client only when one is configured. Without it the application runs
 * normally and question answering falls back to search results.
 */
@Configuration
public class QaConfig {

    private static final Logger log = LoggerFactory.getLogger(QaConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "codeatlas.qa", name = "model")
    public LlmClient llmClient(CodeAtlasProperties properties) {
        CodeAtlasProperties.Qa qa = properties.qa();
        log.info("Question answering will use model {} at {}", qa.model(), qa.baseUrl());
        return new OpenAiCompatibleClient(qa.baseUrl(), qa.apiKey(), qa.model(), qa.maxTokens(),
                Duration.ofSeconds(qa.timeoutSeconds()));
    }
}
