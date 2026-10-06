package com.ai.mall.agent.customer.service.vision;

import com.ai.mall.agent.customer.service.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AfterSaleVisionServiceTest {
    @Test void visibleObservationsAreReviewOnlyAndNoWriteIsPerformed() throws Exception {
        RestTemplate rest=new RestTemplate();var server=MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://ollama/api/chat")).andExpect(jsonPath("$.model").value("qwen3-vl"))
                .andRespond(withSuccess("{\"message\":{\"content\":\"{\\\"observations\\\":[\\\"红色外观\\\"],\\\"draftReason\\\":\\\"待核实\\\"}\"},\"eval_count\":12}",MediaType.APPLICATION_JSON));
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",output);
        var service=new AfterSaleVisionService(rest,new ObjectMapper(),new InputSanitizer(),new SensitiveDataMasker(),"http://ollama/api/chat","qwen3-vl");
        var result=service.inspect(new MockMultipartFile("file","image.png","image/png",output.toByteArray()));
        assertTrue(result.requiresReview());assertEquals(1,result.observations().size());assertEquals(64,result.imageHash().length());server.verify();
        assertThrows(ResponseStatusException.class,()->service.inspect(new MockMultipartFile("file","script.png","image/png","script".getBytes())));
    }
}
