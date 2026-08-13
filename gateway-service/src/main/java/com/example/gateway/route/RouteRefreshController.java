package com.example.gateway.route;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 网关内部管理接口（供 Gateway Dashboard 推送刷新 / 查看生效路由）。
 * 生产环境请将 internal-token 改为强随机值，并限制为内网访问。
 */
@RestController
@RequestMapping("/internal/routes")
public class RouteRefreshController {

    private final RouteRefreshPublisher refreshPublisher;
    private final RouteSyncProperties properties;
    private final RouteDefinitionLocator routeDefinitionLocator;

    public RouteRefreshController(RouteRefreshPublisher refreshPublisher,
                                  RouteSyncProperties properties,
                                  @Qualifier("routeDefinitionLocator") RouteDefinitionLocator routeDefinitionLocator) {
        this.refreshPublisher = refreshPublisher;
        this.properties = properties;
        this.routeDefinitionLocator = routeDefinitionLocator;
    }

    @PostMapping("/refresh")
    public Mono<Map<String, Object>> refresh(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        refreshPublisher.refresh();
        return Mono.just(Map.of("code", 200, "message", "ok"));
    }

    @GetMapping
    public Mono<Map<String, Object>> effective(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireToken(token);
        return routeDefinitionLocator.getRouteDefinitions()
                .map(RouteRefreshController::toSummary)
                .collectList()
                .map(routes -> Map.of("code", 200, "message", "ok", "data", routes));
    }

    private void requireToken(String token) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            return;
        }
        if (token == null || !token.equals(properties.getInternalToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal token");
        }
    }

    private static Map<String, Object> toSummary(RouteDefinition definition) {
        return Map.of(
                "routeId", definition.getId(),
                "uri", definition.getUri() == null ? "" : definition.getUri().toString(),
                "order", definition.getOrder());
    }
}
