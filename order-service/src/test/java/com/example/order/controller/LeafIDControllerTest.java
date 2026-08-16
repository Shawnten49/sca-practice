package com.example.order.controller;

import com.example.common.GlobalExceptionHandler;
import com.example.order.client.LeafIdClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeafIDControllerTest {

    private MockMvc mockMvc;
    private LeafIdClient leafIdClient;

    @BeforeEach
    void setUp() {
        leafIdClient = mock(LeafIdClient.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LeafIDController(leafIdClient))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void segment_usesDefaultKeyAndReturnsResult() throws Exception {
        when(leafIdClient.segmentId("order_id")).thenReturn(1001L);

        mockMvc.perform(get("/leaf/segment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value(1001));
    }

    @Test
    void segment_withCustomKey() throws Exception {
        when(leafIdClient.segmentId("user_id")).thenReturn(2001L);

        mockMvc.perform(get("/leaf/segment").param("key", "user_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2001));
    }

    @Test
    void snowflake_usesDefaultKeyAndReturnsResult() throws Exception {
        when(leafIdClient.snowflakeId("leaf")).thenReturn(2089026014645583910L);

        mockMvc.perform(get("/leaf/snowflake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(2089026014645583910L));
    }
}
