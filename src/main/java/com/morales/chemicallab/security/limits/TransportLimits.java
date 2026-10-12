package com.morales.chemicallab.security.limits;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Initial operational budgets, per API instance. Not a measured capacity claim. */
@Data @Validated @Component @ConfigurationProperties("app.transport")
public class TransportLimits {
    @Min(1) @Max(16777216) private int httpBytes = 4194304;
    @Min(1) @Max(1048576) private int telemetryBytes = 32768;
    @Min(1024) @Max(1048576) private int stompBytes = 65536;
    @Min(1) private int sendBufferBytes = 262144;
    @Min(1) private int sendTimeMs = 10000;
    @Min(1) @Max(64) private int jsonDepth = 12;
    @Min(1) private int jsonStringChars = 5000;
    @Min(1) private int jsonProperties = 32;
    @Min(1) private int jsonCollection = 10000;
    @Min(1) @Max(2000000) private int stateChars = 2000000;
    @Min(1) private int boardObjects = 2000;
    @Min(1) private int boardPoints = 40000;
    @Min(1) @Max(1000) private int eventPoints = 1000;
    @Min(1) @Max(200) private int textRuns = 200;
    @Min(1) @Max(5000) private int textChars = 5000;
    @Min(1) private int coordinate = 100000;
    @Min(1) private int metadataEntries = 32;
    @Min(1) private int metadataKeyChars = 60;
    @Min(1) private int metadataValueChars = 300;
    @Min(1) private int connections = 256;
    @Min(1) private int accountConnections = 3;
    @Min(1) private int accountSubscriptions = 12;
    @Min(1) private int connectionTargets = 4;
    @Min(1) private int rateAccounts = 4096;
    @Min(1) private int rateIdleSeconds = 60;
    @Min(1) private int drawPerSecond = 30;
    @Min(1) private int drawBurst = 60;
    @Min(1) private int framePerSecond = 120;
    @Min(1) private int frameBurst = 240;
    @Min(1) private int presencePerSecond = 1;
    @Min(1) private int presenceBurst = 3;
    @Min(1) private int controlPerSecond = 5;
    @Min(1) private int controlBurst = 10;
    @Min(1) private int telemetryPerSecond = 3;
    @Min(1) private int telemetryBurst = 30;
    @Min(1) private int restPerSecond = 5;
    @Min(1) private int restBurst = 30;
    @AssertTrue(message = "rate-idle-seconds must cover each complete bucket refill")
    public boolean isIdleCoversRefill() {
        return covers(drawBurst, drawPerSecond) && covers(frameBurst, framePerSecond) && covers(presenceBurst, presencePerSecond)
                && covers(controlBurst, controlPerSecond) && covers(telemetryBurst, telemetryPerSecond) && covers(restBurst, restPerSecond);
    }
    private boolean covers(int burst, int rate) { return rate > 0 && (long) rateIdleSeconds * rate >= burst; }
}
