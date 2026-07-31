package com.edutwin.analysis;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.DecoratedRedisConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AnalysisStreamRecovery {

    private final StringRedisTemplate redisTemplate;
    private final String streamName;
    private final String consumerGroup;
    private final String consumerName;
    private final Duration minimumIdleTime;
    private final int batchSize;
    private String cursor = "0-0";

    public AnalysisStreamRecovery(
            StringRedisTemplate redisTemplate,
            @Value("${edutwin.analysis.stream:analysis-events}") String streamName,
            @Value("${edutwin.analysis.consumer-group:edutwin-analysis-v1}") String consumerGroup,
            @Value("${edutwin.analysis.consumer-name:${HOSTNAME:local-backend}}") String consumerName,
            @Value("${edutwin.analysis.reclaim-idle:PT30S}") Duration minimumIdleTime,
            @Value("${edutwin.analysis.reclaim-batch-size:10}") int batchSize) {
        this.redisTemplate = redisTemplate;
        this.streamName = streamName;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.minimumIdleTime = minimumIdleTime;
        this.batchSize = batchSize;
    }

    public synchronized List<MapRecord<String, Object, Object>> claimStale() {
        Object response = redisTemplate.execute((RedisCallback<Object>) connection -> {
            RedisConnection delegate = connection;
            while (delegate instanceof DecoratedRedisConnection decorated) {
                delegate = decorated.getDelegate();
            }
            if (!(delegate instanceof LettuceConnection lettuceConnection)) {
                throw new IllegalStateException(
                        "XAUTOCLAIM requires the configured Lettuce Redis connection.");
            }
            return lettuceConnection.execute(
                    "XAUTOCLAIM",
                    new NestedMultiOutput<>(ByteArrayCodec.INSTANCE),
                    bytes(streamName),
                    bytes(consumerGroup),
                    bytes(consumerName),
                    bytes(Long.toString(minimumIdleTime.toMillis())),
                    bytes(cursor),
                    bytes("COUNT"),
                    bytes(Integer.toString(batchSize)));
        });
        if (response == null) {
            return List.of();
        }
        if (!(response instanceof List<?> parts) || parts.size() < 2) {
            throw new IllegalStateException(
                    "Redis returned an invalid XAUTOCLAIM response of type "
                            + response.getClass().getName() + ": " + response);
        }

        cursor = text(parts.get(0));
        if (!(parts.get(1) instanceof List<?> entries) || entries.isEmpty()) {
            return List.of();
        }
        List<MapRecord<String, Object, Object>> records = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            records.add(record(entry));
        }
        return List.copyOf(records);
    }

    private MapRecord<String, Object, Object> record(Object value) {
        if (!(value instanceof List<?> parts) || parts.size() != 2) {
            throw new IllegalStateException("Redis returned an invalid claimed Stream entry.");
        }
        String recordId = text(parts.get(0));
        Map<Object, Object> fields = fields(parts.get(1));
        return MapRecord.<String, Object, Object>create(streamName, fields)
                .withId(RecordId.of(recordId));
    }

    private static Map<Object, Object> fields(Object value) {
        LinkedHashMap<Object, Object> fields = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, fieldValue) -> fields.put(text(key), text(fieldValue)));
            return fields;
        }
        if (!(value instanceof List<?> values) || values.size() % 2 != 0) {
            throw new IllegalStateException("Redis returned invalid Stream entry fields.");
        }
        for (int index = 0; index < values.size(); index += 2) {
            fields.put(text(values.get(index)), text(values.get(index + 1)));
        }
        return fields;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.asReadOnlyBuffer();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }
}
