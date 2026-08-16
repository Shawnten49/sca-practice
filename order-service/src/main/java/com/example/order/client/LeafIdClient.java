package com.example.order.client;

import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.example.order.config.LeafProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Leaf 分布式 ID 客户端：封装号段 / 雪花两个取号接口。
 *
 * <p>Leaf 返回裸数字字符串；HTTP 失败或返回非数字时快速失败（抛 BusinessException），
 * 不做本地降级，避免 ID 源混用。
 */
@Slf4j
@Component
public class LeafIdClient {

    private static final String SEGMENT_PATH = "/api/segment/get/";
    private static final String SNOWFLAKE_PATH = "/api/snowflake/get/";

    private final RestTemplate restTemplate;
    private final LeafProperties leafProperties;

    public LeafIdClient(RestTemplate restTemplate, LeafProperties leafProperties) {
        this.restTemplate = restTemplate;
        this.leafProperties = leafProperties;
    }

    /** Leaf 号段模式取 ID（key 对应 leaf_alloc 的 biz_tag）。 */
    public long segmentId(String key) {
        return requestId(SEGMENT_PATH, key);
    }

    /** Leaf 雪花模式取 ID。 */
    public long snowflakeId(String key) {
        return requestId(SNOWFLAKE_PATH, key);
    }

    private long requestId(String path, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        String url = leafProperties.getUrl() + path + key;
        try {
            String body = restTemplate.getForObject(url, String.class);
            if (body == null || body.isBlank()) {
                log.error("Leaf 返回为空: url={}", url);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "ID 服务返回为空");
            }
            return Long.parseLong(body.trim());
        } catch (RestClientException e) {
            log.error("Leaf 调用失败: url={}", url, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "ID 服务调用失败");
        } catch (NumberFormatException e) {
            log.error("Leaf 返回非数字: url={}", url, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "ID 服务返回异常");
        }
    }
}
