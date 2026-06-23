package org.sync.compensation;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class EsCompensationRecord {
    private String id;
    private String businessType;
    private String reason;
    private String payload;
    private LocalDateTime createdAt = LocalDateTime.now();
}
