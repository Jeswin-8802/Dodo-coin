package io.mycrypto.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableScheduling
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT_CONNECT = "/ss";
    public static final String SUBSCRIBE_USER_PREFIX = "/peer";
    public static final String APPLICATION_PREFIX = "/signal";

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.setApplicationDestinationPrefixes(APPLICATION_PREFIX);
        config.setUserDestinationPrefix(SUBSCRIBE_USER_PREFIX);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT_CONNECT)
                .setHandshakeHandler(new AssignPrincipalHandshakeHandler())
                .setAllowedOrigins("*");
    }
}
