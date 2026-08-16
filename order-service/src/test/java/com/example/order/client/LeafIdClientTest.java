package com.example.order.client;

import com.example.exception.BusinessException;
import com.example.order.config.LeafProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LeafIdClientTest {

    private LeafIdClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        LeafProperties properties = new LeafProperties();
        properties.setUrl("http://127.0.0.1:8085");
        client = new LeafIdClient(restTemplate, properties);
    }

    @Test
    void segmentId_success() {
        server.expect(requestTo("http://127.0.0.1:8085/api/segment/get/order_id"))
                .andRespond(withSuccess("1001", MediaType.TEXT_PLAIN));

        assertThat(client.segmentId("order_id")).isEqualTo(1001L);
        server.verify();
    }

    @Test
    void snowflakeId_success() {
        server.expect(requestTo("http://127.0.0.1:8085/api/snowflake/get/leaf"))
                .andRespond(withSuccess("2089026014645583910", MediaType.TEXT_PLAIN));

        assertThat(client.snowflakeId("leaf")).isEqualTo(2089026014645583910L);
        server.verify();
    }

    @Test
    void serverError_throwsBusinessException() {
        server.expect(requestTo("http://127.0.0.1:8085/api/segment/get/order_id"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.segmentId("order_id"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nonNumericBody_throwsBusinessException() {
        server.expect(requestTo("http://127.0.0.1:8085/api/segment/get/order_id"))
                .andRespond(withSuccess("not-a-number", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.segmentId("order_id"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blankKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> client.segmentId("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
