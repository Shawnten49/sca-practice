package com.example.user.controller;

import com.example.common.GlobalExceptionHandler;
import com.example.dto.response.UserResponse;
import com.example.user.dto.request.UserCreateRequest;
import com.example.user.service.UserService;
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

class UserControllerTest {

    private MockMvc mockMvc;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void userReturnsFullUserWithMaskedIdCard() throws Exception {
        when(userService.getUserInfo("1")).thenReturn(
                new UserResponse(1L, "demo", 100, 0, "110***********1234", null));

        mockMvc.perform(get("/user").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.nickname").value("demo"))
                .andExpect(jsonPath("$.data.points").value(100))
                .andExpect(jsonPath("$.data.idCard").value("110***********1234"));
    }

    @Test
    void userReturnsFailureWhenSentinelDegraded() throws Exception {
        when(userService.getUserInfo("err")).thenReturn(null);

        mockMvc.perform(get("/user").param("id", "err"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void userNotFoundReturnsProblemDetail() throws Exception {
        when(userService.getUserInfo("999")).thenThrow(
                new com.example.exception.BusinessException(com.example.exception.ErrorCode.NOT_FOUND, "user not found: 999"));

        mockMvc.perform(get("/user").param("id", "999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void saveReturnsSavedUserWithMaskedIdCard() throws Exception {
        when(userService.saveUser("zhangsan", "110101199003071234")).thenReturn(
                new UserResponse(100L, "zhangsan", 0, 0, "110***********1234", null));

        mockMvc.perform(post("/user/save")
                        .contentType("application/json")
                        .content("{\"nickname\":\"zhangsan\",\"idCard\":\"110101199003071234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.idCard").value("110***********1234"));
    }

    @Test
    void saveRejectsInvalidNickname() throws Exception {
        when(userService.saveUser("", "110101199003071234"))
                .thenThrow(new IllegalArgumentException("nickname 不能为空"));

        mockMvc.perform(post("/user/save")
                        .contentType("application/json")
                        .content("{\"nickname\":\"\",\"idCard\":\"110101199003071234\"}"))
                .andExpect(status().isBadRequest());
    }
}
