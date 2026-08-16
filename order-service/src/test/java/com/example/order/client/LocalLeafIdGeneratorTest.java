package com.example.order.client;

import com.example.exception.BusinessException;
import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalLeafIdGeneratorTest {

    private final IDGen segmentIdGen = mock(IDGen.class);
    private final IDGen snowflakeIdGen = mock(IDGen.class);
    private final LocalLeafIdGenerator generator =
            new LocalLeafIdGenerator(segmentIdGen, snowflakeIdGen);

    @Test
    void segmentId_success() {
        when(segmentIdGen.get("order_id")).thenReturn(new Result(1001L, Status.SUCCESS));

        assertThat(generator.segmentId("order_id")).isEqualTo(1001L);
        verify(segmentIdGen).get("order_id");
    }

    @Test
    void snowflakeId_success() {
        when(snowflakeIdGen.get("leaf")).thenReturn(new Result(2089026014645583910L, Status.SUCCESS));

        assertThat(generator.snowflakeId("leaf")).isEqualTo(2089026014645583910L);
    }

    @Test
    void exceptionStatus_throwsBusinessException() {
        when(segmentIdGen.get("order_id")).thenReturn(new Result(0L, Status.EXCEPTION));

        assertThatThrownBy(() -> generator.segmentId("order_id"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blankKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> generator.snowflakeId(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
