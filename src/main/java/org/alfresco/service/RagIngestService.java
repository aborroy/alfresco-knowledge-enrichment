package org.alfresco.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code RagIngestService} is responsible for ingesting Markdown files into a vector database
 * as part of a Retrieval-Augmented Generation (RAG) architecture.
 *
 * <p>This service performs the following operations:
 * <ul>
 *   <li>Parses the input Markdown content into semantically meaningful {@link Document} objects.</li>
 *   <li>Splits the documents into smaller text chunks using a token-based strategy.</li>
 *   <li>Tags each chunk with metadata such as UUID and original filename.</li>
 *   <li>Applies a temporary workaround to ensure chunk size compatibility with current Spring AI limitations.</li>
 *   <li>Stores the final chunks in a {@link VectorStore} for later semantic search or retrieval.</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This implementation includes a temporary patch that splits oversized
 * chunks (text > 1024 characters) into smaller ones (max 512 characters each), due to missing
 * Markdown chunking in Spring AI. Once Spring AI supports this natively, this logic can be removed.
 *
 * <p>Intended for use in AI-powered content enrichment or semantic search applications.
 *
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class RagIngestService {

    private final VectorStore vectorStore;

    /**
     * Processes and ingests a Markdown file into the vector store.
     *
     * <p>This method extracts textual content from the Markdown file, splits it into
     * semantically meaningful chunks, enriches each chunk with metadata, applies a temporary
     * size limitation workaround, and stores the final set of chunks into the vector database.
     *
     * @param uuid A unique identifier associated with this ingestion request
     * @param file The uploaded Markdown file to be ingested
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

            vectorStore.add(fixChunkSize(chunks));

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to ingest Markdown: " + filename, e);
        }
    }

    /**
     * Parses a Markdown file and returns a list of {@link Document} objects extracted from it.
     *
     * <p>This method reads the Markdown content from the given {@link MultipartFile}, wraps it in a
     * Spring {@link Resource}, and processes it using the {@link MarkdownDocumentReader}
     * with default configuration.
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
     * Returns a reusable token-based splitter configuration to divide long texts into overlapping chunks.
     *
     * @return a configured {@link TokenTextSplitter} instance
     */
    private TokenTextSplitter createSplitter() {
        return new TokenTextSplitter(
                256, // chunk size in tokens
                24,            // overlap between adjacent chunks
                10,            // minimum chunk size
                Integer.MAX_VALUE,
                false          // do not preserve empty chunks
        );
    }

    /**
     * Adds identifying metadata to a given {@link Document}, such as the ingestion UUID and source filename.
     *
     * @param doc  the document to annotate
     * @param uuid the UUID used to group this ingestion batch
     * @param name the original filename of the source Markdown
     */
    private static void addMetadata(Document doc, String uuid, String name) {
        doc.getMetadata().put("uuid", uuid);
        doc.getMetadata().put("name", name);
    }

    /**
     * Temporary workaround to split oversized Document chunks into parts
     * of at most 512 characters, but only if the original text exceeds 1024 characters.
     *
     * <p>This prevents ingestion failures or HTTP 500 errors when using Spring AI,
     * which currently lacks native Markdown chunking logic. All metadata from
     * the original Document is preserved.
     *
     * <p>Once Spring AI supports Markdown chunking directly, this method should be removed
     * and replaced with:
     * <pre>{@code
     * vectorStore.add(chunks);
     * }</pre>
     *
     * @param chunks List of original Document chunks
     * @return List of Documents, each with text length ≤ 1024 characters
     */
    private List<Document> fixChunkSize(List<Document> chunks) {
        List<Document> updatedChunks = new ArrayList<>();

        for (Document doc : chunks) {
            String content = doc.getText();
            Map<String, Object> metadata = doc.getMetadata();

            if (content.length() <= 1024) {
                updatedChunks.add(doc);
            } else {
                int start = 0;
                while (start < content.length()) {
                    int end = Math.min(start + 500, content.length());
                    updatedChunks.add(new Document(content.substring(start, end), metadata));
                    start = end;
                }
            }
        }

        return updatedChunks;
    }
}