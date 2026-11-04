package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.shared.application.ClockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the raw control WebSocket (02 §6.1).
 *
 * <p>Allowed origins are {@code *} for this dev slice only: M1's gateway/JWT layer is not wired
 * yet and native clients do not send a browser Origin. Tighten this to the frontend origin before
 * release.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    public static final String CONTROL_PATH = "/ws/v1/sessions/{sessionId}";

    private final ClassroomWebSocketHandler handler;
    private final WsHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(
            ClassroomWebSocketHandler handler,
            WsHandshakeInterceptor handshakeInterceptor) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, CONTROL_PATH)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }

    /**
     * Shared live-connection map. A sink that throws unregisters the connection and starts the
     * 30 s grace timer through the actor; a second connection simply replaces the sink.
     */
    @Bean
    public static WsConnectionRegistry wsConnectionRegistry(
            SessionRuntimeRegistry runtimeRegistry, ClockProvider clockProvider) {
        return new WsConnectionRegistry(sessionId -> runtimeRegistry.find(sessionId)
                .ifPresent(actor -> actor.onWsDisconnected(clockProvider.now())));
    }
}
