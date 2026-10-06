package com.ai.mall.agent.customer.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MemberIdentityResolverTest {
    @Test void memberIdComesFromVerifiedPortalAndBearerIsForwarded() {
        RestTemplate rest=new RestTemplate();var server=MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://portal/sso/info")).andExpect(header("Authorization","Bearer verified-token"))
                .andRespond(withSuccess("{\"code\":200,\"data\":{\"id\":7}}",MediaType.APPLICATION_JSON));
        var context=new MemberIdentityResolver(rest,new ObjectMapper(),"http://portal").resolve("s1","Bearer verified-token");
        assertEquals("7",context.getMemberId());assertFalse(context.isWriteApproved());server.verify();
    }
    @Test void invalidIdentityFailsClosedAndNeverFallsBackToAnonymous() {
        RestTemplate rest=new RestTemplate();var server=MockRestServiceServer.bindTo(rest).build();
        server.expect(anything()).andRespond(withSuccess("{\"code\":401,\"data\":null}",MediaType.APPLICATION_JSON));
        var resolver=new MemberIdentityResolver(rest,new ObjectMapper(),"http://portal");
        assertThrows(ResponseStatusException.class,()->resolver.resolve("s1","Bearer invalid-token"));
        assertFalse(resolver.resolve("s1",null).isAuthenticated());
        assertThrows(ResponseStatusException.class,()->resolver.resolve("../other",null));
        server.verify();
    }
    @Test void approvalHashIsIndependentOfObjectOrderAndSensitiveToValues() {
        assertEquals(OperationFingerprint.hash("{\"a\":1,\"b\":2}"),OperationFingerprint.hash("{\"b\":2,\"a\":1}"));
        assertNotEquals(OperationFingerprint.hash("{\"a\":1}"),OperationFingerprint.hash("{\"a\":2}"));
    }
    @Test void tokenAloneCannotApproveWrite() {
        var context=com.ai.mall.agent.customer.model.ToolInvocationContext.builder().memberId("7").userToken("token").build();
        assertFalse(new ToolAccessGuard().authorize("place_order",context).isAllowed());
    }
}
