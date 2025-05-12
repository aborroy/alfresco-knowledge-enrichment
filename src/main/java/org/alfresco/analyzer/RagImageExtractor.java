package org.alfresco.analyzer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagImageExtractor {

    private final ChatModel chatModel;
    private static final int MIN_IMAGE_WIDTH = 50;
    private static final int MIN_IMAGE_HEIGHT = 50;
    private static final int MAX_CONTEXT_LENGTH = 500;

    /**
     * Analyzes all figures in a PDF file and returns AI-generated descriptions with metadata.
     *
     * @param pdfResource The PDF resource to analyze
     * @param uuid A unique identifier for the document
     * @param filename The original filename of the document
     * @return List of documents containing figure descriptions
     */
    public List<Document> analyzeFiguresFromPdf(Resource pdfResource, String uuid, String filename) {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdfResource.getInputStream()))) {
            return IntStream.range(0, document.getNumberOfPages())
                    .parallel()
                    .mapToObj(pageIndex -> analyzePageSafe(document, pageIndex, uuid, filename))
                    .flatMap(List::stream)
                    .toList();

        } catch (IOException e) {
            log.error("Failed to load PDF [{}]: {}", filename, e.getMessage());
            return List.of();
        }
    }

    /**
     * Safely analyzes a single page. Returns an empty list on failure.
     */
    private List<Document> analyzePageSafe(PDDocument doc, int pageIndex, String uuid, String filename) {
        try {
            return analyzePage(doc, pageIndex, uuid, filename);
        } catch (Exception e) {
            log.warn("Error processing page {} of [{}]: {}", pageIndex + 1, filename, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extracts and describes all non-trivial figures from a given page.
     */
    private List<Document> analyzePage(PDDocument document, int pageIndex, String uuid, String filename) throws IOException {
        int pageNumber = pageIndex + 1;
        PDPage page = document.getPage(pageIndex);

        // Extract text first to help with figure detection
        String pageText = extractPageText(document, pageNumber);
        List<Figure> detectedFigures = detectFiguresFromText(pageText);

        // Extract images
        List<Document> result = new ArrayList<>(extractImageFigures(document, page, pageNumber, uuid, filename, pageText));

        // Extract figures detected from a text
        for (Figure figure : detectedFigures) {
            Document doc = new Document(figure.description);
            doc.getMetadata().put("uuid", uuid);
            doc.getMetadata().put("name", filename);
            doc.getMetadata().put("page", String.valueOf(pageNumber));
            doc.getMetadata().put("type", figure.type);
            result.add(doc);
        }

        return result;
    }

    /**
     * Extracts and describes images from a page.
     */
    private List<Document> extractImageFigures(PDDocument document, PDPage page, int pageNumber,
                                               String uuid, String filename, String pageText) throws IOException {
        List<Document> result = new ArrayList<>();

        // Extract images from page resources
        List<PDImageXObject> images = extractImagesFromPage(page);
        for (PDImageXObject image : images) {
            if (isTooSmall(image)) continue;

            byte[] imageBytes = convertToPng(image);
            String context = extractContextFromText(pageText);
            String description = describeImage(imageBytes, pageNumber, context);

            Document doc = new Document(description);
            doc.getMetadata().put("uuid", uuid);
            doc.getMetadata().put("name", filename);
            doc.getMetadata().put("page", String.valueOf(pageNumber));
            doc.getMetadata().put("type", "image");
            doc.getMetadata().put("width", String.valueOf(image.getWidth()));
            doc.getMetadata().put("height", String.valueOf(image.getHeight()));
            result.add(doc);
        }

        return result;
    }

    /**
     * Detects figures from page text using advanced regex patterns.
     */
    private List<Figure> detectFiguresFromText(String pageText) {
        List<Figure> figures = new ArrayList<>();

        // Enhanced regex to capture various figure types
        Pattern figurePattern = Pattern.compile(
                "(Figure|Fig\\.?|Chart|Table|Image|Graph|Diagram|Illustration)\\s*(\\d+)?[.:]?\\s*([^\\n\\.]+)",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = figurePattern.matcher(pageText);

        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase();
            String number = matcher.group(2) != null ? matcher.group(2) : "N/A";
            String description = matcher.group(3).trim();

            // Generate a more comprehensive description
            String fullDescription = generateFigureDescription(type, number, description, pageText);

            figures.add(new Figure(type, fullDescription));
        }

        return figures;
    }

    /**
     * Generates a comprehensive description for a detected figure.
     */
    private String generateFigureDescription(String type, String number, String caption, String pageText) {
        try {
            String prompt = "Analyze the following figure information:\n" +
                    "Type: " + type + "\n" +
                    "Number: " + number + "\n" +
                    "Caption: " + caption + "\n" +
                    "Page Context: " + summarize(pageText) + "\n\n" +
                    "Provide a concise, informative description of what this figure likely represents. " +
                    "If the information is insufficient, generate a plausible description based on the context.";

            return ChatClient.create(chatModel)
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();

        } catch (Exception e) {
            log.warn("LLM figure description failed: {}", e.getMessage());
            return type + " on the page: " + caption;
        }
    }

    /**
     * Extracts all XObject images from a page, including form XObjects which might contain images.
     */
    private List<PDImageXObject> extractImagesFromPage(PDPage page) throws IOException {
        List<PDImageXObject> images = new ArrayList<>();
        PDResources resources = page.getResources();

        if (resources == null) return images;

        for (COSName name : resources.getXObjectNames()) {
            PDXObject obj = resources.getXObject(name);
            if (obj instanceof PDImageXObject image) {
                images.add(image);
            } else if (obj instanceof PDFormXObject form) {
                // Recursively extract images from form XObjects
                PDResources formResources = form.getResources();
                if (formResources != null) {
                    for (COSName formName : formResources.getXObjectNames()) {
                        PDXObject formObj = formResources.getXObject(formName);
                        if (formObj instanceof PDImageXObject formImage) {
                            images.add(formImage);
                        }
                    }
                }
            }
        }

        return images;
    }

    /**
     * Determines if an image is too small to be meaningful (likely decoration).
     */
    private boolean isTooSmall(PDImageXObject image) throws IOException {
        BufferedImage img = image.getImage();
        return img.getWidth() < MIN_IMAGE_WIDTH || img.getHeight() < MIN_IMAGE_HEIGHT;
    }

    /**
     * Converts a PDImageXObject to PNG bytes.
     */
    private byte[] convertToPng(PDImageXObject image) throws IOException {
        BufferedImage buffered = image.getImage();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(buffered, "PNG", out);
            return out.toByteArray();
        }
    }

    /**
     * Extracts text from a specific page.
     */
    private String extractPageText(PDDocument document, int pageNumber) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            return stripper.getText(document);
        } catch (IOException e) {
            log.warn("Text extraction failed on page {}: {}", pageNumber, e.getMessage());
            return "";
        }
    }

    /**
     * Attempts to extract caption-like context from page text.
     */
    private String extractContextFromText(String text) {
        if (text == null || text.isBlank()) return "";

        Matcher matcher = Pattern.compile("(Figure|Fig\\.?|Chart|Table|Image)\\s+\\d+[.:]\\s*([^\\n\\.]+)").matcher(text);
        return matcher.find() ? matcher.group(0) : summarize(text);
    }

    /**
     * Shortens long context to a usable preview.
     */
    private String summarize(String text) {
        return text.length() <= MAX_CONTEXT_LENGTH ? text : text.substring(0, MAX_CONTEXT_LENGTH).trim() + "...";
    }

    /**
     * Sends the image and optional context to the LLM and retrieves a description.
     */
    private String describeImage(byte[] imageBytes, int pageNumber, String context) {
        try {
            String prompt = buildPrompt(pageNumber, context);

            String result = ChatClient.create(chatModel)
                    .prompt()
                    .user(user -> user.text(prompt)
                            .media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes)))
                    .call()
                    .content();

            return (result == null || result.isBlank())
                    ? "Image on page " + pageNumber + " of the document."
                    : result;

        } catch (Exception e) {
            log.warn("LLM image description failed on page {}: {}", pageNumber, e.getMessage());
            return "Image on page " + pageNumber + " of the document.";
        }
    }

    /**
     * Builds the prompt to send to the LLM, including optional contextual clues.
     */
    private String buildPrompt(int pageNumber, String context) {
        return "You are analyzing an image from page " + pageNumber + " of a PDF document. " +
                "Provide a detailed description of what you see: subject, visual elements, visible text, charts, or diagrams. " +
                (context.isBlank() ? "" : "This image appears with the following context: \"" + context + "\". ") +
                "Your description should be 2–3 sentences, concise but informative, useful for future search or summarization.";
    }

    /**
     * Inner class to represent a detected figure.
     */
    private static class Figure {
        String type;
        String description;

        Figure(String type, String description) {
            this.type = type;
            this.description = description;
        }
    }
}