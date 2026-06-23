package org.sync.query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.sync.model.BookingWideDoc;
import org.sync.service.BookingWideEsService;

@Service
public class BookingWideQueryService {

    private final BookingWideEsService bookingWideEsService;

    public BookingWideQueryService(BookingWideEsService bookingWideEsService) {
        this.bookingWideEsService = bookingWideEsService;
    }

    public Page<BookingWideDoc> page(Pageable pageable) {
        return bookingWideEsService.searchAll(pageable);
    }
}
