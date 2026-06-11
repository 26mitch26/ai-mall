package com.ai.mall.agent.test;

import com.ai.mall.agent.test.config.AgentTestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

@SpringBootApplication
@EnableConfigurationProperties(AgentTestConfig.class)
public class AgentTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentTestApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder, AgentTestConfig config) {
        // Configure connection pooling
        PoolingHttpClientConnectionManager connectionManager = createConnectionManager(config);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionManagerShared(true)
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        return builder
                .requestFactory(() -> requestFactory)
                .setConnectTimeout(Duration.ofMillis(config.getConnectTimeout()))
                .setReadTimeout(Duration.ofMillis(config.getReadTimeout()))
                .build();
    }

    private PoolingHttpClientConnectionManager createConnectionManager(AgentTestConfig config) {
        try {
            SSLContext sslContext = SSLContexts.custom()
                    .loadTrustMaterial(null, (chain, authType) -> true)
                    .build();

            Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.getSocketFactory())
                    .register("https", new SSLConnectionSocketFactory(sslContext, new DefaultHostnameVerifier()))
                    .build();

            PoolingHttpClientConnectionManager connectionManager =
                    new PoolingHttpClientConnectionManager(socketFactoryRegistry);

            connectionManager.setMaxTotal(config.getMaxConnections());
            connectionManager.setDefaultMaxPerRoute(config.getMaxConnectionsPerRoute());

            connectionManager.setDefaultSocketConfig(SocketConfig.custom()
                    .setSoTimeout(Timeout.ofMilliseconds(config.getReadTimeout()))
                    .build());

            return connectionManager;
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            // Fallback to basic connection manager if SSL setup fails
            PoolingHttpClientConnectionManager connectionManager =
                    new PoolingHttpClientConnectionManager();
            connectionManager.setMaxTotal(config.getMaxConnections());
            connectionManager.setDefaultMaxPerRoute(config.getMaxConnectionsPerRoute());
            return connectionManager;
        }
    }
}