package org.sync.service;

import org.sync.model.BookingWideDoc;
import org.sync.model.BookingWideIndexNames;
import org.sync.repository.BookingWideRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BookingWideEsService {

    private final BookingWideRepository repository;

    public BookingWideEsService(BookingWideRepository repository) {
        this.repository = repository;
    }

    public void upsert(BookingWideDoc doc) {
        if (doc == null || doc.getId() == null) return;
        repository.save(doc);
    }

    public void upsertAll(List<BookingWideDoc> docs) {
        if (docs == null || docs.isEmpty()) return;
        repository.saveAll(docs);
    }

    public void deleteById(String id) {
        if (id == null || id.isBlank()) return;
        repository.deleteById(id);
    }

    public void deleteAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        ids.forEach(this::deleteById);
    }

    public Page<BookingWideDoc> searchAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    public String writeIndex() {
        return BookingWideIndexNames.WRITE_ALIAS;
    }

    public String readIndex() {
        return BookingWideIndexNames.READ_ALIAS;
    }

    public String queryIndex() {
        return BookingWideIndexNames.QUERY_ALIAS;
    }
}
