package com.example.task.mapper;

import com.example.entity.User;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    @Test
    void shardAndCursorPagination() throws Exception {
        SqlSessionFactory factory = H2MapperFactory.create(
                "CREATE TABLE users (id BIGINT PRIMARY KEY, nickname VARCHAR(64) NOT NULL, "
                        + "points INT NOT NULL, create_time DATETIME NOT NULL)",
                "INSERT INTO users VALUES (1, 'u1', 10, '2026-08-18 10:00:00')",
                "INSERT INTO users VALUES (2, 'u2', 20, '2026-08-18 10:00:00')",
                "INSERT INTO users VALUES (3, 'u3', 30, '2026-08-18 10:00:00')",
                "INSERT INTO users VALUES (4, 'u4', 40, '2026-08-18 10:00:00')",
                "INSERT INTO users VALUES (5, 'u5', 50, '2026-08-18 10:00:00')",
                "INSERT INTO users VALUES (6, 'u6', 60, '2026-08-18 10:00:00')",
                "INSERT INTO users VALUES (7, 'u7', 70, '2026-08-18 10:00:00')");

        try (SqlSession session = factory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);

            // shardTotal=2, shardIndex=0 → MOD(id,2)=0 → 2,4,6
            List<User> page1 = mapper.selectByShard(0L, 0, 2, 2);
            assertThat(page1).extracting(User::getId).containsExactly(2L, 4L);

            List<User> page2 = mapper.selectByShard(4L, 0, 2, 2);
            assertThat(page2).extracting(User::getId).containsExactly(6L);

            assertThat(mapper.selectByShard(6L, 0, 2, 2)).isEmpty();

            // shardIndex=1 → 1,3,5,7
            List<User> odd = mapper.selectByShard(0L, 1, 2, 10);
            assertThat(odd).extracting(User::getId).containsExactly(1L, 3L, 5L, 7L);
        }
    }
}
