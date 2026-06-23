package org.sync.compensation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class EsCompensationService {

    private final CopyOnWriteArrayList<EsCompensationRecord> records = new CopyOnWriteArrayList<>();

    public void record(String businessType, String reason, String payload) {
        EsCompensationRecord record = new EsCompensationRecord();
        record.setBusinessType(businessType);
        record.setReason(reason);
        record.setPayload(payload);
        records.add(record);
        log.warn("[ES-COMPENSATION] businessType={}, reason={}", businessType, reason);
    }

    public List<EsCompensationRecord> list() {
        return records;
    }

    public void clear() {
        records.clear();
    }
}
