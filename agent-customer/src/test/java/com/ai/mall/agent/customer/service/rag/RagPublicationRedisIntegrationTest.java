package com.ai.mall.agent.customer.service.rag;

import com.ai.mall.agent.customer.model.Document;
import org.junit.jupiter.api.*;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Lua and serializers; simulated vector writes never enter the live evaluation Redis database. */
@Tag("integration")
class RagPublicationRedisIntegrationTest {
    @Test void immediateUpdatesFollowPublicationTimeAndFutureVersionsWait() {
        int port=Integer.getInteger("agent.verify.redis-port",-1);
        Assumptions.assumeTrue(port>0,"Use a dedicated verification Redis");
        RedisStandaloneConfiguration config=new RedisStandaloneConfiguration("127.0.0.1",port);config.setDatabase(15);
        LettuceConnectionFactory connection=new LettuceConnectionFactory(config);connection.afterPropertiesSet();connection.start();
        try {
            StringRedisTemplate redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
            RagService rag=new RagService(null,null,mock(VectorStore.class),redis,null,null);
            String source="publish-"+UUID.randomUUID()+".md";
            Instant t=Instant.parse("2026-10-03T00:00:00Z");
            ReflectionTestUtils.setField(rag,"clock",Clock.fixed(t,ZoneOffset.UTC));
            Document first=Document.builder().id("old").source(source).type("policy").content("审批时间24小时。").build();
            rag.indexDocuments(List.of(first));
            assertEquals(first.getVersion(),rag.activeVersionForSource(source));
            ReflectionTestUtils.setField(rag,"clock",Clock.fixed(t.plusSeconds(1),ZoneOffset.UTC));
            Document second=Document.builder().id("new").source(source).type("policy").content("审批时间36小时。").build();
            rag.indexDocuments(List.of(second));
            assertEquals(second.getVersion(),rag.activeVersionForSource(source));
            Document future=Document.builder().id("future").source(source).type("policy").content("审批时间48小时。").effectiveAt(t.plusSeconds(3)).build();
            rag.indexDocuments(List.of(future));
            assertEquals(second.getVersion(),rag.activeVersionForSource(source));
            ReflectionTestUtils.setField(rag,"clock",Clock.fixed(t.plusSeconds(4),ZoneOffset.UTC));
            assertEquals(future.getVersion(),rag.activeVersionForSource(source));
            assertEquals("审批时间24小时。",rag.findSourceRevision(source,first.getVersion()).get("content"));
        } finally { connection.destroy(); }
    }
}
