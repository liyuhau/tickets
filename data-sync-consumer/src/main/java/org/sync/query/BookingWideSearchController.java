package org.sync.query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sync.model.BookingWideDoc;

@RestController
@RequestMapping("/internal/es/booking-wide")
public class BookingWideSearchController {

    private final BookingWideQueryService queryService;

    public BookingWideSearchController(BookingWideQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/page")
    public Page<BookingWideDoc> page(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return queryService.page(PageRequest.of(page, size));
    }
}
