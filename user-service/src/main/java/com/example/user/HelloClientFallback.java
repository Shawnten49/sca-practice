package com.example.user;

import org.springframework.stereotype.Component;

@Component
public class HelloClientFallback implements HelloClient {
    @Override
    public String hello() {
        return "[fallback] hello-service 不可用";
    }

    @Override
    public String slow() {
        return "[fallback] hello-service 超时兜底";
    }
}