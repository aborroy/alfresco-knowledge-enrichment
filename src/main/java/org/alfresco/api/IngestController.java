package org.alfresco.api;

import lombok.RequiredArgsConstructor;
import org.alfresco.service.RagIngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class IngestController {

    private final RagIngestService ragIngestService;

    /**
     * Ingests the given Markdown file and associates it with the provided UUID.
     *
     * @param uuid Identifier used for grouping or tracking the document.
     * @param file Must be a Markdown file (validated by MIME type).
     * @return HTTP 202 Accepted if ingestion is successful.
     */
    @PostMapping("/ingest")
    public ResponseEntity<String> ingest(@RequestParam String uuid,
                                         @RequestParam MultipartFile file) {

        String contentType = file.getContentType();
        String filename = file.getOriginalFilename();

        boolean isMarkdown = "text/markdown".equalsIgnoreCase(contentType) ||
                "text/x-markdown".equalsIgnoreCase(contentType) ||
                (filename != null && filename.toLowerCase().endsWith(".md"));

        if (!isMarkdown) {
            return ResponseEntity.badRequest().body(
                    "File " + filename + " + mimetype is " + contentType + ", only Markdown files are supported.");
        }

        ragIngestService.process(uuid, file);
        return ResponseEntity.accepted().body("Indexed: " + file.getOriginalFilename());
    }
}
