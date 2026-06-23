package org.sync.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.sync.model.BookingWideDoc;

@Slf4j
@Configuration
public class ElasticsearchBootstrap implements ApplicationRunner {

    private final ElasticsearchOperations operations;

    public ElasticsearchBootstrap(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @Override
    public void run(ApplicationArguments args) {
        IndexOperations indexOps = operations.indexOps(BookingWideDoc.class);
        if (!indexOps.exists()) {
            indexOps.create();
            indexOps.putMapping(indexOps.createMapping(BookingWideDoc.class));
            log.info("[ES] booking_wide_v1 index created, alias booking_wide should point here");
            return;
        }
        log.info("[ES] booking_wide_v1 index already exists");
    }
}
