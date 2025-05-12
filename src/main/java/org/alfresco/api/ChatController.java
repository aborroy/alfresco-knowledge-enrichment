package org.alfresco.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.alfresco.service.RagQueryService;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final RagQueryService ragQueryService;

    /**
     * Accepts a chat message and returns the AI-generated response along with source documents.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        return ResponseEntity.ok(ragQueryService.chat(request.message));
    }

    /**
     * Incoming request payload containing the user message.
     */
    @Getter
    @RequiredArgsConstructor
    public static class ChatRequest {
        private final String message;
    }

    /**
     * Response payload containing the AI-generated response and optional supporting documents.
     */
    @Getter
    @RequiredArgsConstructor
    public static class ChatResponse {
        private final String response;
        private final List<Document> documents;
    }
}