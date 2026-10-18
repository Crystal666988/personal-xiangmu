package com.tradeagent.core.dag;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IntentRouterTest {
    private final IntentRouter router = new IntentRouter();

    @Test
    void unionsImageKnowledgeAndCommerceToolsInStableOrder() {
        var route = router.route("分析架构并给出报价", "截图.png");
        assertEquals("COMPOSITE", route.domain());
        assertEquals(9, route.activeTools().size());
        var ids = route.activeTools().stream().map(IntentRouter.Tool::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
        assertEquals(ids, router.route("报价，分析架构", "截图.png").activeTools().stream().map(IntentRouter.Tool::id).toList());
        assertEquals(6, route.prunedToolsCount());
    }

    @Test
    void avoidsEnglishSubstringMatchesAndSupportsAttachments() {
        assertEquals("GENERAL", router.route("hello service pipeline", null).domain());
        assertEquals(List.of(), router.route("你好", null).activeTools());
        assertEquals("VISION", router.route("", "photo.JPG").domain());
        assertEquals("KNOWLEDGE", router.route("", "manual.pdf").domain());
        assertEquals("COMMERCE", router.route("PI quote", null).domain());
    }
}
