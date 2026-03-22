package com.uj.cfg.cfgjscrapper.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.uj.cfg.cfgjscrapper.cfg.MethodCfg;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class RepoFileCfg {
    private String filepath;
    private List<MethodCfg> graphs = new ArrayList<>();

    public RepoFileCfg() {
    }

    public RepoFileCfg(String filepath, List<MethodCfg> graphs) {
        this.filepath = filepath;
        this.graphs = graphs;
    }

    public String getFilepath() {
        return filepath;
    }

    public List<MethodCfg> getGraphs() {
        return graphs;
    }
}

