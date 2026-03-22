package com.github.barney.canonicallog.app.interceptor;

import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
import com.github.barney.canonicallog.lib.masking.SensitiveMasker;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class ApplicationClientHttpInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

        Instant start = Instant.now();
        String service = request.getURI().getHost();
        String endpoint = request.getURI().getPath();
        String httpMethod = request.getMethod().name();
        Map<String, String> requestHeaders = flattenHeaders(request.getHeaders());

        try {

            ClientHttpResponse response = execution.execute(request, body);
            long duration = Duration.between(start, Instant.now()).toMillis();
            Map<String, String> responseHeaders = flattenHeaders(response.getHeaders());

            CanonicalLogContext context = ObservabilityContext.logContext();
            if (context != null) {
                context.addEvent(new CanonicalLogContext.OutboundLogEvent(
                        start, service, endpoint, httpMethod, response.getStatusCode().value(),
                        duration, requestHeaders, responseHeaders, null
                ));
            }

            return response;
        } catch (Exception e) {

            long duration = Duration.between(start, Instant.now()).toMillis();

            CanonicalLogContext context = ObservabilityContext.logContext();
            if (context != null) {
                context.addEvent(new CanonicalLogContext.OutboundLogEvent(
                        start, service, endpoint, httpMethod, 0, duration, requestHeaders,
                        Map.of(), e.getClass().getSimpleName() + ": " + e.getMessage()
                ));
            }

            throw e;
        }
    }

    private Map<String, String> flattenHeaders(HttpHeaders headers) {

        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }

        Map<String, String> flattenedHeaders = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                flattenedHeaders.put(name, String.join(", ", values));
            }
        });

        return flattenedHeaders;
    }
}
