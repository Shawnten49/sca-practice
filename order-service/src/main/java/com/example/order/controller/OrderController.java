package com.example.order.controller;

import com.example.order.service.OrderDubboService;
import com.example.order.service.OrderMqService;
import com.example.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;

    private final OrderDubboService orderDubboService;

    private final OrderMqService orderMqService;

    public OrderController(OrderService orderService, OrderDubboService orderDubboService, OrderMqService orderMqService) {
        this.orderService = orderService;
        this.orderDubboService = orderDubboService;
        this.orderMqService = orderMqService;
    }

    @GetMapping("/order/create")
    public String create(@RequestParam Long userId,
                         @RequestParam Long productId,
                         @RequestParam Integer count,
                         @RequestParam(defaultValue = "false") boolean fail) {
        return orderService.createOrder(userId, productId, count, fail);
    }

    @GetMapping("/order/create2")
    public String create2(@RequestParam Long userId,
                         @RequestParam Long productId,
                         @RequestParam Integer count,
                         @RequestParam(defaultValue = "false") boolean fail) {
        return orderDubboService.createOrder(userId, productId, count, fail);
    }

    @GetMapping("/order/create3")
    public String create3(@RequestParam Long userId,
                          @RequestParam Long productId,
                          @RequestParam Integer count,
                          @RequestParam(defaultValue = "false") boolean fail) {
        orderMqService.createOrder(userId, productId, count);

        return "success";
    }
}
