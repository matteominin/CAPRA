package com.example.demo.service;

import com.example.demo.config.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client for the Flask PDF extraction service.
 * Delegates all parsing logic (text + images via OpenAI Vision) to the Python service.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final RestClient restClient;

    public DocumentIngestionService(AuditProperties properties) {
        // Generous timeout: extraction with OpenAI Vision on large PDFs can take minutes
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(Duration.ofMinutes(5));

        this.restClient = RestClient.builder()
                .baseUrl(properties.pdfService().baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Sends the PDF to the Flask service and returns the full extracted text,
     * including image descriptions generated via OpenAI Vision.
     *
     * @param file the PDF file uploaded by the user
     * @return full text of the document
     * @throws RuntimeException if the extraction fails
     */
    public String extractFullText(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
        log.info("Sending PDF '{}' ({} bytes) to extraction service", filename, file.getSize());

        try {
            byte[] fileBytes = file.getBytes();

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            body.add("mode", "full");

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/extract")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                String text = (String) response.get("text");
                log.info("Extraction completed: {} characters extracted", text.length());
                return text;
            }

            String error = response != null ? response.toString() : "null response from service";
            throw new RuntimeException("PDF extraction failed: " + error);

        } catch (IOException e) {
            throw new RuntimeException("Unable to read the uploaded file: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new RuntimeException(
                    "PDF extraction service unreachable. Ensure the Flask service is running on "
                            + "the configured port. Details: " + e.getMessage(), e);
        }
    }

    /**
     * Checks whether the Flask service is reachable.
     */
    public boolean isServiceAvailable() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> health = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);
            return health != null && "ok".equals(health.get("status"));
        } catch (Exception e) {
            log.warn("PDF extraction service not available: {}", e.getMessage());
            return false;
        }
    }
}
