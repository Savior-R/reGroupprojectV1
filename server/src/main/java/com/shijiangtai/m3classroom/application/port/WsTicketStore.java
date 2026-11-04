package com.shijiangtai.m3classroom.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * One-time, short-lived WebSocket ticket store (02 §2.3).
 *
 * <p>{@link #consume(String)} is a destructive read: a ticket can be redeemed at most once.
 */
public interface WsTicketStore {

    String issue(WsTicketClaims claims, Duration ttl);

    Optional<WsTicketClaims> consume(String ticket);
}
