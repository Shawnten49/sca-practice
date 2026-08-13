package com.example.gateway.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DbRouteDefinitionLocatorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DbRouteDefinitionLocator locator =
            new DbRouteDefinitionLocator(jdbcTemplate, new ObjectMapper());

    @Test
    void loadsRoutesFromDbAndParsesPredicates() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(new RouteConfigRow(
                        "user-route",
                        "lb://user-service",
                        1,
                        "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/user/**\"}}]",
                        "[]",
                        "{}")));

        StepVerifier.create(locator.getRouteDefinitions())
                .assertNext(definition -> {
                    assertThat(definition.getId()).isEqualTo("user-route");
                    assertThat(definition.getUri().toString()).isEqualTo("lb://user-service");
                    assertThat(definition.getPredicates()).hasSize(1);
                    assertThat(definition.getPredicates().get(0).getName()).isEqualTo("Path");
                    assertThat(definition.getPredicates().get(0).getArgs())
                            .containsEntry("pattern", "/user/**");
                })
                .verifyComplete();

        verify(jdbcTemplate).query(
                eq("SELECT route_id, uri, order_no, predicates_json, filters_json, metadata_json "
                        + "FROM route_config WHERE enabled = TRUE ORDER BY order_no ASC, id ASC"),
                any(RowMapper.class));
    }

    @Test
    void emptyTableYieldsEmptyFlux() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        StepVerifier.create(locator.getRouteDefinitions())
                .verifyComplete();
    }

    @Test
    void malformedPredicatesJsonFailsWithContext() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(new RouteConfigRow(
                        "bad-route",
                        "lb://x",
                        1,
                        "not-json",
                        "[]",
                        "{}")));

        StepVerifier.create(locator.getRouteDefinitions())
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("路由断言/过滤器配置解析失败"))
                .verify();
    }
}
