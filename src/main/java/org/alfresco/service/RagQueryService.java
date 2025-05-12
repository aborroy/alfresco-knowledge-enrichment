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

@Service
@RequiredArgsConstructor
public class RagQueryService {

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    private static final PromptTemplate PROMPT_TEMPLATE = new PromptTemplate("""
        You are an expert assistant. Use the following context to answer the user's question.
        If the context does not contain the answer, reply with "I don't know."

        Context:
        {context}

        Question:
        {question}
        """);

    /**
     * Answers a user query using LLM + vector store context.
     *
     * @param userInput The user’s question.
     * @return A response from the model and the documents used as context.
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
