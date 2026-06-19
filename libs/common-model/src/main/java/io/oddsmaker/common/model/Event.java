package io.oddsmaker.common.model;

import java.util.Map;

public class Event {
    public String eventId;
    public String gameId;
    public String environment;
    public String eventType;
    public String eventName;
    public String userId;     // optional
    public String deviceId;
    public String sessionId;  // optional
    public long tsClient;     // epoch millis
    public Long tsServer;     // epoch millis, nullable
    public String platform;
    public String appVersion;
    public String country;
    public String clientIp;   // derived from request
    public String userAgent;  // derived from request
    public Double revenueAmount;
    public String revenueCurrency;
    public Map<String, Object> props;
}
