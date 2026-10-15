package com.psicocare.shared;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Bounded calls without logging headers, bodies or provider error payloads. */
public final class OutboundHttp {
    private OutboundHttp() {}

    public static RestClient client(String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
