package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.application.SessionRuntimeRegistry;
import com.shijiangtai.shared.application.ClockProvider;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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

    /**
     * Container buffer above the 64 KB protocol limit: frames between 64 KB and this size must
     * reach the handler so it can close with the M3 close code 4005 rather than the container's
     * generic 1009. The handler still enforces 64 KB on the UTF-8 byte length.
     */
    public static final int MAX_FRAME_BYTES = 256 * 1024;

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
     * Raises the servlet container's text/binary buffer above the protocol's 64 KB limit.
     *
     * <p>{@code @SpringBootTest} MOCK web contexts have no
     * {@code jakarta.websocket.server.ServerContainer} attribute, so the factory only configures
     * a real embedded container instead of failing context startup.
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container =
                new ServletServerContainerFactoryBean() {
                    private ServletContext servletContext;

                    @Override
                    public void setServletContext(ServletContext servletContext) {
                        this.servletContext = servletContext;
                        super.setServletContext(servletContext);
                    }

                    @Override
                    public void afterPropertiesSet() {
                        if (servletContext == null
                                || servletContext.getAttribute(
                                        ServerContainer.class.getName()) == null) {
                            return;
                        }
                        super.afterPropertiesSet();
                    }
                };
        container.setMaxTextMessageBufferSize(MAX_FRAME_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_FRAME_BYTES);
        return container;
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
