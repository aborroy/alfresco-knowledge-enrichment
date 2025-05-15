package org.alfresco.service;

import lombok.RequiredArgsConstructor;
import org.alfresco.api.ChatController;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * {@code RagQueryService} handles semantic question answering using
 * a Retrieval-Augmented Generation (RAG) approach.
 *
 * <p>This service combines a vector store with a large language model (LLM) to answer
 * user questions using contextually relevant documents. It performs the following steps:
 * <ul>
 *   <li>Performs a semantic similarity search on the vector store based on the user query.</li>
 *   <li>Constructs a prompt using a predefined template that includes the retrieved context.</li>
 *   <li>Sends the prompt to the underlying LLM for generating a response.</li>
 *   <li>Returns both the generated answer and the documents used as context.</li>
 * </ul>
 *
 * <p>This service is typically called by the controller layer to serve chat or Q&A interactions
 * based on a knowledge base indexed previously using {@link RagIngestService}.
 *
 * <p>Designed for AI-powered assistants and smart search systems.
 *
 */
@Service
@RequiredArgsConstructor
public class RagQueryService {

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    /**
     * A reusable prompt template that instructs the model to answer using only the provided context.
     * If the context is insufficient, it instructs the model to respond with "I don't know."
     */
    private static final PromptTemplate PROMPT_TEMPLATE = new PromptTemplate("""
        You are an expert assistant. Use the following context to answer the user's question.
        If the context does not contain the answer, reply with "I don't know."

        Context:
        {context}

        Question:
        {question}
        """);

    /**
     * Answers a user query by performing semantic search against the vector store
     * and generating a response using a large language model (LLM).
     *
     * <p>The logic follows the standard RAG pattern:
     * <ol>
     *   <li>Perform a similarity search using the user query.</li>
     *   <li>Concatenate the resulting {@link Document} texts as prompt context.</li>
     *   <li>Invoke the LLM with a prompt composed from the template and the input.</li>
     *   <li>Return the model's response along with the documents used for context.</li>
     * </ol>
     *
     * @param userInput The user’s natural-language question.
     * @return A {@link ChatController.ChatResponse} containing the model's answer and supporting context documents.
     */
    public ChatController.ChatResponse chat(String userInput) {
        // 1. Search for relevant context documents
        List<Document> contextDocs = vectorStore.similaritySearch(userInput);

        if (contextDocs.isEmpty()) {
            // Fallback: no relevant context
            return new ChatController.ChatResponse(
                    "No relevant information found to answer your question.",
                    List.of()
            );
        }

        // 2. Concatenate document texts to form the context
        String context = contextDocs.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);

        // 3. Build and send prompt
        Prompt prompt = PROMPT_TEMPLATE.create(Map.of(
                "context", context,
                "question", userInput
        ));

        String answer = ChatClient.create(chatModel)
                .prompt(prompt)
                .call()
                .content();

        return new ChatController.ChatResponse(answer, contextDocs);
    }
}