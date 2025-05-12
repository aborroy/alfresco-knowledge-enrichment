package org.alfresco.service;

import lombok.RequiredArgsConstructor;
import org.alfresco.analyzer.RagImageExtractor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RagIngestService {

    private final VectorStore vectorStore;
    private final RagImageExtractor ragImageExtractor;

    /**
     * Ingests a PDF file, extracts text and images, adds metadata, and stores it in the vector database.
     *
     * @param uuid Identifier to tag each extracted document chunk.
     * @param file The uploaded PDF file.
     */
    public void process(String uuid, MultipartFile file) {
        final String filename = file.getOriginalFilename();

        try {
            // Convert an uploaded file into a Spring Resource
            Resource pdf = new ByteArrayResource(file.getBytes(), filename);

            // Extract text chunks
            List<Document> textChunks = createSplitter()
                    .apply(new PagePdfDocumentReader(pdf).get())
                    .stream()
                    .peek(doc -> addMetadata(doc, uuid, filename))
                    .toList();

            // Extract image chunks
            List<Document> imageChunks = ragImageExtractor.analyzeFiguresFromPdf(pdf, uuid, filename);

            // Store all chunks in the vector database
            vectorStore.add(Stream.concat(textChunks.stream(), imageChunks.stream()).toList());

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to ingest PDF: " + filename, e);
        }
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
