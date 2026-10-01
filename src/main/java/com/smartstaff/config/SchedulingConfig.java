package com.smartstaff.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled jobs (assessment deadline auto-submit). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
