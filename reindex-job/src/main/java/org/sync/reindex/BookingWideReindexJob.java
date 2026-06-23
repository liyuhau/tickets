package org.sync.reindex;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.sync.model.BookingWideDoc;
import org.sync.service.BookingWideEsService;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BookingWideReindexJob implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final BookingWideEsService bookingWideEsService;

    @Value("${cdc.reindex.enabled:true}")
    private boolean enabled;

    @Value("${cdc.reindex.page-size:500}")
    private int pageSize;

    @Value("${cdc.reindex.start-offset:0}")
    private long startOffset;

    public BookingWideReindexJob(JdbcTemplate jdbcTemplate, BookingWideEsService bookingWideEsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.bookingWideEsService = bookingWideEsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[REINDEX] disabled");
            return;
        }
        long offset = startOffset;
        long total = 0L;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM booking ORDER BY id LIMIT ? OFFSET ?", pageSize, offset);
            if (rows.isEmpty()) {
                break;
            }
            List<BookingWideDoc> docs = rows.stream().map(BookingWideDoc::fromCdcAfter).toList();
            bookingWideEsService.upsertAll(docs);
            total += rows.size();
            offset += rows.size();
            log.info("[REINDEX] batch done, rows={}, offset={}", rows.size(), offset);
        }
        log.info("[REINDEX] completed, totalRows={}", total);
    }
}
