package com.example.demo.service;

import com.example.demo.config.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.ClientHttpRequestFactory;
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
 * Client HTTP per il servizio Flask di estrazione PDF.
 * Delega tutta la logica di parsing (testo + immagini via OpenAI Vision) al servizio Python.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final RestClient restClient;

    public DocumentIngestionService(AuditProperties properties) {
        // Timeout generoso: l'estrazione con OpenAI Vision su PDF grandi può richiedere minuti
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(Duration.ofMinutes(5));

        this.restClient = RestClient.builder()
                .baseUrl(properties.pdfService().baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Invia il PDF al servizio Flask e restituisce il testo completo estratto,
     * incluse le descrizioni delle immagini generate via OpenAI Vision.
     *
     * @param file il file PDF caricato dall'utente
     * @return testo completo del documento
     * @throws RuntimeException se l'estrazione fallisce
     */
    public String extractFullText(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
        log.info("Invio PDF '{}' ({} bytes) al servizio di estrazione", filename, file.getSize());

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
                log.info("Estrazione completata: {} caratteri estratti", text.length());
                return text;
            }

            String error = response != null ? response.toString() : "risposta nulla dal servizio";
            throw new RuntimeException("Estrazione PDF fallita: " + error);

        } catch (IOException e) {
            throw new RuntimeException("Impossibile leggere il file caricato: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new RuntimeException(
                    "Servizio di estrazione PDF non raggiungibile. Assicurati che il servizio Flask sia attivo su "
                            + "la porta configurata. Dettaglio: " + e.getMessage(), e);
        }
    }

    /**
     * Verifica che il servizio Flask sia raggiungibile.
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
            log.warn("Servizio di estrazione PDF non disponibile: {}", e.getMessage());
            return false;
        }
    }
}
