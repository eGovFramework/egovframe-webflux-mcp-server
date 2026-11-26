package com.example.webflux.service;

import reactor.core.publisher.Mono;

public interface EgovDateTimeService {

    Mono<String> getCurrentDateTimeWithZone(String zoneId);
}
