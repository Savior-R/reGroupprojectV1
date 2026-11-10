package com.shijiangtai.m7teacher.infrastructure.config;

import com.shijiangtai.m7teacher.domain.FinalScoreCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** M7 wiring: the pure calculator and the 5-minute final-score reconciler. */
@Configuration
@EnableScheduling
public class M7Config {

    @Bean
    public FinalScoreCalculator finalScoreCalculator() {
        return new FinalScoreCalculator();
    }
}
