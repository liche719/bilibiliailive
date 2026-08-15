package com.bilibili.ailive.liveplatform.bilibili;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliConnectionControllerTest {

    @Test
    void connectsAndReturnsTheLatestStatus() {
        BilibiliLiveEventConnector connector = mock(BilibiliLiveEventConnector.class);
        BilibiliConnectionStatus connected = new BilibiliConnectionStatus(
                BilibiliConnectionState.CONNECTED,
                123L,
                "game-id",
                null,
                null
        );
        when(connector.status()).thenReturn(connected);
        BilibiliConnectionController controller = new BilibiliConnectionController(connector);

        BilibiliConnectionStatus result = controller.connect();

        verify(connector).connect();
        assertEquals(connected, result);
    }

    @Test
    void disconnectsAndReturnsTheLatestStatus() {
        BilibiliLiveEventConnector connector = mock(BilibiliLiveEventConnector.class);
        BilibiliConnectionStatus disconnected = BilibiliConnectionStatus.disconnected();
        when(connector.status()).thenReturn(disconnected);
        BilibiliConnectionController controller = new BilibiliConnectionController(connector);

        BilibiliConnectionStatus result = controller.disconnect();

        verify(connector).disconnect();
        assertEquals(disconnected, result);
    }
}
