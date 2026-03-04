package com.fu.conversation.context;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = com.fu.conversation.ConversationTestApplication.class)
@ActiveProfiles("test")
class ConversationContextLoadTest {

    @Test
    void contextLoads() {
    }
}

