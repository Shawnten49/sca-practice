package com.example.gateway.route;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class RouteRefreshPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public RouteRefreshPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void refresh() {
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
    }
}
