package com.example.user.controller;

import com.example.common.GlobalExceptionHandler;
import com.example.user.dto.request.UserProfileSaveRequest;
import com.example.user.dto.response.UserProfileResponse;
import com.example.user.service.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserProfileControllerTest {

    private MockMvc mockMvc;
    private UserProfileService userProfileService;

    @BeforeEach
    void setUp() {
        userProfileService = mock(UserProfileService.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(new UserProfileController(userProfileService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void saveReturnsSavedProfile() throws Exception {
        when(userProfileService.save(any(UserProfileSaveRequest.class))).thenReturn(
                new UserProfileResponse(1L, "demo", List.of("vip"), Map.of("level", 5),
                        LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(post("/user/profile/save")
                        .contentType("application/json")
                        .content("{\"userId\":1,\"nickname\":\"demo\",\"tags\":[\"vip\"],\"extra\":{\"level\":5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.nickname").value("demo"))
                .andExpect(jsonPath("$.data.tags[0]").value("vip"))
                .andExpect(jsonPath("$.data.extra.level").value(5));
    }

    @Test
    void queryReturnsProfile() throws Exception {
        when(userProfileService.query(1L)).thenReturn(
                new UserProfileResponse(1L, "demo", List.of("vip"), Map.of("level", 5),
                        LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(get("/user/profile").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nickname").value("demo"));
    }

    @Test
    void queryMissReturnsNullData() throws Exception {
        when(userProfileService.query(999L)).thenReturn(null);

        mockMvc.perform(get("/user/profile").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }
}
