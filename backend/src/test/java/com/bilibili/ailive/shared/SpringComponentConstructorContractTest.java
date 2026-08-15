package com.bilibili.ailive.shared;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringComponentConstructorContractTest {

    private static final List<String> COMPONENTS_WITH_TEST_CONSTRUCTORS = List.of(
            "com.bilibili.ailive.conversation.LiveHostConversationService",
            "com.bilibili.ailive.conversation.ReplyRetentionService",
            "com.bilibili.ailive.conversation.RoomReplyScheduler",
            "com.bilibili.ailive.conversation.ViewerWelcomeCoordinator",
            "com.bilibili.ailive.liveplatform.DanmakuDispatchService",
            "com.bilibili.ailive.liveplatform.OutboundDanmakuEchoGuard",
            "com.bilibili.ailive.liveplatform.RedisLiveAudienceTracker",
            "com.bilibili.ailive.liveplatform.bilibili.BilibiliRequestSigner",
            "com.bilibili.ailive.overlay.OverlayHub",
            "com.bilibili.ailive.runtime.RuntimeControlService"
    );

    @Test
    void everyComponentWithMultipleConstructorsMarksItsProductionConstructor() throws ClassNotFoundException {
        for (String className : COMPONENTS_WITH_TEST_CONSTRUCTORS) {
            Class<?> componentType = Class.forName(className);
            boolean hasAutowiredConstructor = java.util.Arrays.stream(componentType.getDeclaredConstructors())
                    .anyMatch(constructor -> constructor.isAnnotationPresent(Autowired.class));

            assertTrue(hasAutowiredConstructor, () -> className + " must mark its production constructor with @Autowired");
        }
    }
}
