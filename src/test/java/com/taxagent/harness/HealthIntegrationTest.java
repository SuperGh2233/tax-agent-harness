package com.taxagent.harness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class HealthIntegrationTest {
    @LocalServerPort
    private int port;

    @Test
    void healthRespondsOverHttp() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(MediaType.parseMediaType(response.headers().firstValue("content-type").orElseThrow())
                    .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            var mapper = new ObjectMapper();
            assertThat(mapper.readTree(response.body()))
                    .isEqualTo(mapper.readTree("{\"status\":\"UP\"}"));
        }
    }
}
