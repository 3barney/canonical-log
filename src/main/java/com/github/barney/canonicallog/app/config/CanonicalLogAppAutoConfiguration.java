package com.github.barney.canonicallog.app.config;

import com.github.barney.canonicallog.app.interceptor.ApplicationClientHttpInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@ComponentScan(basePackages = "com.github.barney.canonicallog.app")
public class CanonicalLogAppAutoConfiguration {

    @Bean
    public RestClient canonicalRestClient() {
        return RestClient.builder()
                .requestInterceptor(new ApplicationClientHttpInterceptor())
                .build();
    }
}
