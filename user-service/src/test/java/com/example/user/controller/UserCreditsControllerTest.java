package com.example.user.controller;

import com.example.common.GlobalExceptionHandler;
import com.example.dto.response.CreditsResponse;
import com.example.user.service.UserCreditsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserCreditsControllerTest {

    private MockMvc mockMvc;
    private UserCreditsService userCreditsService;

    @BeforeEach
    void setUp() {
        userCreditsService = mock(UserCreditsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserCreditsController(userCreditsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void getCreditsReturnsResultWrapper() throws Exception {
        when(userCreditsService.getCredits(1L)).thenReturn(new CreditsResponse(1L, 0));

        mockMvc.perform(get("/user/credits").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.credits").value(0));
    }

    @Test
    void getCreditsRejectsInvalidUserId() throws Exception {
        when(userCreditsService.getCredits(0L))
                .thenThrow(new IllegalArgumentException("userId 必须是正整数"));

        mockMvc.perform(get("/user/credits").param("userId", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCreditsReturnsLatestCredits() throws Exception {
        when(userCreditsService.updateCredits(1L, 100)).thenReturn(new CreditsResponse(1L, 100));

        mockMvc.perform(post("/user/credits/update").param("userId", "1").param("delta", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.credits").value(100));
    }

    @Test
    void updateCreditsRejectsInvalidDelta() throws Exception {
        when(userCreditsService.updateCredits(1L, 0))
                .thenThrow(new IllegalArgumentException("delta 不能为 0"));

        mockMvc.perform(post("/user/credits/update").param("userId", "1").param("delta", "0"))
                .andExpect(status().isBadRequest());
    }
}
