package com.example.user.controller;

import com.example.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    @Value("${server.port}")
    private String port;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/info")
    public String info() {
        return "user-service running on port " + port;
    }

    @GetMapping("/user")
    public String user(@RequestParam String id) {
        return userService.getUserInfo(id);
    }


}