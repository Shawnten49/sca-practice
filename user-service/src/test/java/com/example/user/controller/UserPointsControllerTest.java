package com.example.user.controller;

import com.example.common.GlobalExceptionHandler;
import com.example.user.dto.PointsVO;
import com.example.user.service.UserPointsService;
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

class UserPointsControllerTest {

    private MockMvc mockMvc;
    private UserPointsService userPointsService;

    @BeforeEach
    void setUp() {
        userPointsService = mock(UserPointsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserPointsController(userPointsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void getPointsReturnsResultWrapper() throws Exception {
        when(userPointsService.getPoints(1L)).thenReturn(new PointsVO(1L, 100));

        mockMvc.perform(get("/user/points").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.points").value(100));
    }

    @Test
    void getPointsRejectsInvalidUserId() throws Exception {
        mockMvc.perform(get("/user/points").param("userId", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePointsReturnsLatestPoints() throws Exception {
        when(userPointsService.updatePoints(1L, 100)).thenReturn(new PointsVO(1L, 200));

        mockMvc.perform(post("/user/points/update").param("userId", "1").param("delta", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.points").value(200));
    }

    @Test
    void updatePointsRejectsInvalidDelta() throws Exception {
        when(userPointsService.updatePoints(1L, 0))
                .thenThrow(new IllegalArgumentException("delta 不能为 0"));

        mockMvc.perform(post("/user/points/update").param("userId", "1").param("delta", "0"))
                .andExpect(status().isBadRequest());
    }
}
