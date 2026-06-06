package com.unfurl.fabric.studio;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StudioIntentRequest {
    public String tenantId;
    public String assemblyId;
    public String sessionId;
    public long baseRevision;
    public String type;
    public String collaboratorId;
    public String collaboratorName;
    private final Map<String, Object> payload = new LinkedHashMap<>();

    @JsonAnySetter
    public void put(String key, Object value) {
        if (!"tenantId".equals(key)
                && !"assemblyId".equals(key)
                && !"sessionId".equals(key)
                && !"baseRevision".equals(key)
                && !"type".equals(key)
                && !"collaboratorId".equals(key)
                && !"collaboratorName".equals(key)) {
            payload.put(key, value);
        }
    }

    @JsonAnyGetter
    public Map<String, Object> payload() {
        return payload;
    }
}
