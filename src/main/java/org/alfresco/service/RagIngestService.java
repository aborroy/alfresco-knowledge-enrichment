package org.alfresco.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Log4j2
@Service
@RequiredArgsConstructor
public class RagIngestService {

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    /**
     * Ingests a Markdown file, extracts text and images, adds metadata, and stores it in the vector database.
     *
     * @param uuid Identifier to tag each extracted document chunk.
     * @param file The uploaded Markdown file.
     */
    public void process(String uuid, MultipartFile file) {
        final String filename = file.getOriginalFilename();

        try {
            final List<Document> documents = getDocuments(file, filename);

            List<Document> chunks = createSplitter()
                    .apply(documents)
                    .stream()
                    .peek(doc -> addMetadata(doc, uuid, filename))
                    .toList();

            // Add chunks to vector store if not empty
            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
                log.info("Ingestion complete: {} text chunks, {} image chunks",
                        chunks.size(), chunks.size());
            } else {
                log.warn("No chunks extracted from document: {}", filename);
            }

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to ingest Markdown: " + filename, e);
        }
    }

    /**
     * Parses a Markdown file and returns a list of {@link Document} objects extracted from it.
     *
     * <p>This method reads the Markdown content from the given {@link MultipartFile}, wraps it in a
     * Spring {@link Resource}, and processes it using the {@link MarkdownDocumentReader} with default configuration.</p>
     *
     * @param file     The uploaded Markdown file as a {@link MultipartFile}
     * @param filename The original filename, used to label the resource
     * @return A list of parsed {@link Document} objects extracted from the Markdown content
     * @throws IOException If there is an error reading the file content
     */
    private static List<Document> getDocuments(MultipartFile file, String filename) throws IOException {
        String markdown = new String(file.getBytes(), StandardCharsets.UTF_8);

        Resource markdownResource = new ByteArrayResource(markdown.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename != null ? filename : "markdown_content.md";
            }
        };

        MarkdownDocumentReader reader = new MarkdownDocumentReader(
                markdownResource, MarkdownDocumentReaderConfig.defaultConfig());
        return reader.get();
    }


    /**
     * Returns a reusable splitter configuration for breaking text into chunks.
     */
    private TokenTextSplitter createSplitter() {
        return new TokenTextSplitter(
                256, // chunk size
                50,            // overlap between chunks
                10,            // minimum chunk size
                Integer.MAX_VALUE,
                false          // preserve empty chunks = false
        );
    }

    /**
     * Adds identifying metadata to each document chunk.
     */
    private static void addMetadata(Document doc, String uuid, String name) {
        doc.getMetadata().put("uuid", uuid);
        doc.getMetadata().put("name", name);
    }
}
