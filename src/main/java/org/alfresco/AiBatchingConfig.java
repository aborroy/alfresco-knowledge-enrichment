package org.alfresco;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.embedding.BatchingStrategy;

import java.util.Collections;

@Configuration
public class AiBatchingConfig {

    /**
     * Disable batching completely: each Document goes in an independent list,
     * so Spring AI will POST exactly one chunk per /embeddings call.
     */
    @Bean
    BatchingStrategy batchingStrategy() {
        return docs -> docs.stream()
                .map(Collections::singletonList)
                .toList();
    }
}