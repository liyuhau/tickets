package org.sync.repository;

import org.sync.model.BookingWideDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface BookingWideRepository extends ElasticsearchRepository<BookingWideDoc, String> {
}
