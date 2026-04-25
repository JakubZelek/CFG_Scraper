package com.uj.cfg.cfgjscrapper.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoFileCfgSerializationTest {

    @Test
    void keepsGraphsFieldWhenEmpty() throws Exception {
        RepoFileCfg payload = new RepoFileCfg("/tmp/repo/A.java", java.util.List.of());
        String json = new ObjectMapper().writeValueAsString(payload);

        assertTrue(json.contains("\"graphs\":[]"), "Expected graphs field in serialized payload: " + json);
    }
}

