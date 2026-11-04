package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import java.util.Objects;

/** Session/account binding carried by a one-time WebSocket ticket (02 §2.3). */
public record WsTicketClaims(SessionId sessionId, AccountId accountId) {
    public WsTicketClaims {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(accountId, "accountId");
    }
}
