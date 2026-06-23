package org.sync.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;
import org.sync.model.BookingWideDoc;

@Component
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private final ElasticsearchOperations operations;

    public ElasticsearchHealthIndicator(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @Override
    public Health health() {
        try {
            boolean exists = operations.indexOps(BookingWideDoc.class).exists();
            return exists ? Health.up().withDetail("index", BookingWideDoc.class.getSimpleName()).build()
                          : Health.down().withDetail("index", "missing").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
