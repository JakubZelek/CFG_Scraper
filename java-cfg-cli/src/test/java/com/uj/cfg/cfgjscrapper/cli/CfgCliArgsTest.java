package com.uj.cfg.cfgjscrapper.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CfgCliArgsTest {

    @Test
    void parsesRequiredArgs() {
        CfgCliApplication.CliArgs args = CfgCliApplication.CliArgs.parse(new String[]{
                "--repo-root", "/tmp/repo",
                "--filepath", "/tmp/repo/A.java"
        });

        assertEquals("/tmp/repo", args.repoRoot());
        assertEquals("/tmp/repo/A.java", args.filepath());
    }

    @Test
    void failsWhenRepoRootMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> CfgCliApplication.CliArgs.parse(new String[]{"--filepath", "/tmp/repo/A.java"}));
    }
}

