package com.dgm.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "business")
public class BusinessProperties {
    private String name;
    private String shortName;
    private String ownerPhone;
    private String timezone;
    private double afterHoursSurcharge;
    private String tagline;
}
