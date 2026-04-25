package com.uj.cfg.cfgjscrapper.cli;

import com.uj.cfg.cfgjscrapper.cfg.MethodCfg;

import java.util.ArrayList;
import java.util.List;

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

