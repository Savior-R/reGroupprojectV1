package com.shijiangtai.m3classroom.infrastructure.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shijiangtai.m3classroom.TestFixtures;
import com.shijiangtai.m3classroom.application.port.WsTicketClaims;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class RedisWsTicketStoreTest {

    private static final SessionId SESSION_ID =
            new SessionId(UUID.fromString("00000000-0000-0000-0000-000000000600"));
    private static final WsTicketClaims CLAIMS =
            new WsTicketClaims(SESSION_ID, TestFixtures.STUDENT);

    @Test
    void storesSnakeCaseClaimsWithTtlAndConsumesWithGetDelete() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisWsTicketStore store = new RedisWsTicketStore(redis, JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build());

        String ticket = store.issue(CLAIMS, Duration.ofSeconds(30));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("ws_ticket:" + ticket), json.capture(), eq(Duration.ofSeconds(30)));
        assertThat(json.getValue())
                .contains("\"session_id\"")
                .contains("\"account_id\"")
                .contains(TestFixtures.STUDENT_ID.toString());

        when(values.getAndDelete("ws_ticket:" + ticket)).thenReturn(json.getValue());
        assertThat(store.consume(ticket)).contains(CLAIMS);
        verify(values).getAndDelete("ws_ticket:" + ticket);
    }

    @Test
    void missingTicketConsumesToEmpty() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisWsTicketStore store = new RedisWsTicketStore(
                redis, JsonMapper.builder().build());

        assertThat(store.consume("missing")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume(" ")).isEmpty();
    }
}
