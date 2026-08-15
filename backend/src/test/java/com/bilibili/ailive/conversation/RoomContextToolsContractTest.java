package com.bilibili.ailive.conversation;

import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomContextToolsContractTest {

    @Test
    void exposesAParametersSchemaRequiredByResponsesApis() throws NoSuchMethodException {
        var method = RoomContextTools.class.getMethod(
                "recentRoomConversation",
                String.class,
                String.class
        );

        String specification = ToolSpecifications.toolSpecificationFrom(method).toJson();

        assertTrue(specification.contains("\"parameters\""));
        assertTrue(specification.contains("\"reason\""));
    }
}
