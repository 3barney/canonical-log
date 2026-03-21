package com.github.barney.canonicallog.app.interceptor;

import com.github.barney.canonicallog.lib.context.CanonicalLogContext;
import com.github.barney.canonicallog.lib.context.ObservabilityContext;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

public class ApplicationClientHttpInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {

        Instant start = Instant.now();
        String service = request.getURI().getHost();
        String endpoint = request.getURI().getPath();
        String httpMethod = request.getMethod().name();

        try {

            ClientHttpResponse response = execution.execute(request, body);
            long duration = Duration.between(start, Instant.now()).toMillis();

            CanonicalLogContext context = ObservabilityContext.logContext();
            if (context != null) {
                context.addEvent(new CanonicalLogContext.OutboundLogEvent(
                        start, service, endpoint, httpMethod,
                        response.getStatusCode().value(), duration, null
                ));
            }

            return response;
        } catch (Exception e) {

            long duration = Duration.between(start, Instant.now()).toMillis();

            CanonicalLogContext context = ObservabilityContext.logContext();
            if (context != null) {
                context.addEvent(new CanonicalLogContext.OutboundLogEvent(
                        start, service, endpoint, httpMethod,
                        0, duration, e.getClass().getSimpleName() + ": " + e.getMessage()
                ));
            }

            throw e;
        }
    }
}
