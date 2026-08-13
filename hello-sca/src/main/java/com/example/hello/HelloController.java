package com.example.hello;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @Value("${server.port}")
    private String port;

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Spring Boot! (port " + port + ")";
    }

    @GetMapping("/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(2000);
        return "slow response (port " + port + ")";
    }
}