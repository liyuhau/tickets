package org.sync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cdc.es")
public class ElasticsearchProperties {

    private String indexPrefix = "";
    private String wideIndex = "booking_wide";
    private boolean autoCreateIndex = true;
    private String refreshPolicy = "wait_for";

    public String getIndexPrefix() { return indexPrefix; }
    public void setIndexPrefix(String indexPrefix) { this.indexPrefix = indexPrefix; }

    public String getWideIndex() { return wideIndex; }
    public void setWideIndex(String wideIndex) { this.wideIndex = wideIndex; }

    public boolean isAutoCreateIndex() { return autoCreateIndex; }
    public void setAutoCreateIndex(boolean autoCreateIndex) { this.autoCreateIndex = autoCreateIndex; }

    public String getRefreshPolicy() { return refreshPolicy; }
    public void setRefreshPolicy(String refreshPolicy) { this.refreshPolicy = refreshPolicy; }
}
