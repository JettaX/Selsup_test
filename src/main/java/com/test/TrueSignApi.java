package com.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TrueSignApi {
    private static final String BASE_URL = "https://ismp.crpt.ru/api/v3";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String accessToken;
    private final RateLimiter limiter;
    private final ObjectMapper objectMapper;

    public TrueSignApi(TimeUnit timeUnit, int requestLimit, String accessToken) {
        limiter = new RateLimiter(requestLimit, timeUnit);
        objectMapper = new ObjectMapper();
        this.accessToken = accessToken;
    }

    public CreatedIntroductionDocument createIntroductionDocument(IntroductionDocument document, String signature) throws Exception {
        try {

            controlLimits();

            DocumentSubmission submission = new DocumentSubmission(document, signature);
            String requestBody = objectMapper.writeValueAsString(submission);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/lk/documents/commissioning/contract/create"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return (CreatedIntroductionDocument) processResponse(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка обработки документа: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при выполнении запроса: " + e.getMessage(), e);
        }


    }

    private void controlLimits() throws InterruptedException {
        while (!limiter.tryAcquire()) {
            Thread.sleep(Math.min(limiter.getDelayMillis(), 100));
        }
    }

    private Object processResponse(HttpResponse<String> response) {
        try {
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), CreatedIntroductionDocument.class);
            } else if (response.statusCode() == 401) {
                throw new RuntimeException("Ошибка авторизации: требуется повторная авторизация или обновление токена.");
            } else {
                FailedResponse failedResponse = objectMapper.readValue(response.body(), FailedResponse.class);
                throw new RuntimeException("Ошибка API: " + failedResponse.error_message + " (code: " + failedResponse.code + ") - " + failedResponse.description);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка обработки ответа: " + e.getMessage(), e);
        }
    }

    @AllArgsConstructor
    @Getter
    public class DocumentSubmission {
        private IntroductionDocument document;
        private String signature;
    }

    @AllArgsConstructor
    @Getter
    public class FailedResponse {
        private String code;
        private String error_message;
        private String description;
    }

    @AllArgsConstructor
    @Getter
    public class CreatedIntroductionDocument {
        private String value;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    public class IntroductionDocument {
        private Description description;
        private String doc_id;
        private String doc_status;
        private String doc_type;
        private Boolean importRequest;
        private String owner_inn;
        private String participant_inn;
        private String producer_inn;
        private String production_date;
        private ProductionType production_type;
        private List<Product> products;
        private String reg_date;
        private String reg_number;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    public class Description {
        private String participantInn;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    public class Product {
        private CertificateDocumentType certificate_document;
        private String certificate_document_date;
        private String certificate_document_number;
        private String owner_inn;
        private String producer_inn;
        private String production_date;
        private String tnved_code;
        private String uit_code;
        private String uitu_code;
    }

    public enum ProductionType {
        OWN_PRODUCTION,
        CONTRACT_PRODUCTION
    }

    public enum CertificateDocumentType {
        CONFORMITY_CERTIFICATE,
        CONFORMITY_DECLARATION,
    }

    public class RateLimiter {
        private final int requestLimit;
        private final long timeWindowMillis;
        private final Deque<Long> requestTimestamps = new ArrayDeque<>();

        public RateLimiter(int requestLimit, TimeUnit timeUnit) {
            this.requestLimit = requestLimit;
            this.timeWindowMillis = timeUnit.toMillis(1);
        }

        public synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();

            clearOuts(now);

            if (requestTimestamps.size() < requestLimit) {
                requestTimestamps.addLast(now);
                return true;
            }
            return false;
        }

        public synchronized long getDelayMillis() {
            long now = System.currentTimeMillis();

            clearOuts(now);

            if (requestTimestamps.size() < requestLimit) {
                return 0;
            } else {
                return timeWindowMillis - (now - requestTimestamps.peekFirst()) + 1;
            }
        }

        private synchronized void clearOuts(long now) {
            while (!requestTimestamps.isEmpty() && now - requestTimestamps.peekFirst() >= timeWindowMillis) {
                requestTimestamps.pollFirst();
            }
        }
    }

}