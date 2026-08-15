package com.bilibili.ailive.shared;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
class LocalOnlyServerGuard {

    private final String serverAddress;

    LocalOnlyServerGuard(@Value("${server.address}") String serverAddress) {
        this.serverAddress = serverAddress;
    }

    @PostConstruct
    void requireLoopbackAddress() {
        try {
            if (!InetAddress.getByName(serverAddress).isLoopbackAddress()) {
                throw new IllegalStateException(
                        "The control panel must remain bound to a loopback address until operator authentication is implemented"
                );
            }
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Invalid server.address: " + serverAddress, exception);
        }
    }
}
