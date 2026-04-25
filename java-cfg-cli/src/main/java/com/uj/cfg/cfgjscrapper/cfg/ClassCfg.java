package com.uj.cfg.cfgjscrapper.cfg;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ClassCfg {
    private String filepath;
    private String className;
    private List<MethodCfg> graphs;

    public ClassCfg(String filepath, String className, List<MethodCfg> graphs) {
        this.filepath = filepath;
        this.className = className;
        this.graphs = graphs;
    }

    public String getFilepath() {
        return filepath;
    }

    public String getClassName() {
        return className;
    }

    public List<MethodCfg> getGraphs() {
        return graphs;
    }
}

