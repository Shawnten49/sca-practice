package com.example.user.controller;

import com.example.user.HelloClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CallController {

    private final HelloClient helloClient;

    public CallController(HelloClient helloClient) {
        this.helloClient = helloClient;
    }

    @GetMapping("/call-hello")
    public String callHello() {
        return "user-service got: " + helloClient.hello();
    }

    @GetMapping("/call-slow")
    public String callSlow() {
        long start = System.currentTimeMillis();
        String result = helloClient.slow();
        return "user-service got: " + result + " (" + (System.currentTimeMillis() - start) + "ms)";
    }
}