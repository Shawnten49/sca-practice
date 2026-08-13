package com.example.user;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "hello-service", fallback = HelloClientFallback.class)
public interface HelloClient {

    @GetMapping("/hello")
    String hello();

    @GetMapping("/slow")
    String slow();
}