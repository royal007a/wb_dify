package com.hify.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpErrorContractTest {
    private MockMvc http;
    private static final String PRIVATE_INPUT = "sensitive-test-input-not-for-response";

    @BeforeEach void setup() {
        http = MockMvcBuilders.standaloneSetup(new ContractController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void missingRequiredHeaderIsBadRequest() throws Exception {
        error(get("/contract/items").param("page", "1"), 400, 40000);
    }

    @Test void missingRequiredQueryIsBadRequest() throws Exception {
        error(get("/contract/items").header("Idempotency-Key", "test"), 400, 40000);
    }

    @Test void invalidQueryAndPathTypesDoNotEchoSubmittedValue() throws Exception {
        error(get("/contract/items").param("page", PRIVATE_INPUT).header("Idempotency-Key", "test"), 400, 40000);
        error(get("/contract/items/{id}", PRIVATE_INPUT), 400, 40000);
    }

    @Test void unsupportedMethodUses405AndPreservesAllow() throws Exception {
        error(delete("/contract/items"), 405, 40500);
        http.perform(delete("/contract/items")).andExpect(header().string("Allow", containsString("GET")));
    }

    @Test void unsupportedMediaUses415() throws Exception {
        error(post("/contract/items").contentType(MediaType.TEXT_PLAIN).content(PRIVATE_INPUT), 415, 41500);
    }

    @Test void unsupportedAcceptUses406AndStillHasSafeErrorEnvelope() throws Exception {
        error(get("/contract/items").param("page", "1").header("Idempotency-Key", "test")
                .accept(MediaType.TEXT_EVENT_STREAM), 406, 40600);
    }

    @Test void malformedJsonDoesNotEchoSubmittedValue() throws Exception {
        error(post("/contract/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + PRIVATE_INPUT), 400, 40000);
    }

    private void error(MockHttpServletRequestBuilder request, int status, int code) throws Exception {
        http.perform(request).andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(content().string(not(containsString(PRIVATE_INPUT))));
    }

    @RestController static class ContractController {
        @GetMapping(value = "/contract/items", produces = "application/json")
        public Result<String> list(@RequestParam("page") int page, @RequestHeader("Idempotency-Key") String key) {
            return Result.ok("ok");
        }
        @GetMapping("/contract/items/{id}")
        public Result<Integer> get(@PathVariable("id") int id) { return Result.ok(id); }
        @PostMapping(value = "/contract/items", consumes = "application/json")
        public Result<String> create(@RequestBody Map<String, Object> body) { return Result.ok("ok"); }
    }
}
