package com.uj.cfg.cfgjscrapper.cfg;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class MethodCfg {
    @JsonProperty("name")
    private String name;

    @JsonProperty("graph_dict")
    private Map<String, String[]> graphDict;

    @JsonProperty("in_degrees")
    private String inDegrees;

    @JsonProperty("out_degrees")
    private String outDegrees;

    @JsonProperty("other_graph_info")
    private Map<String, Object> otherGraphInfo;

    public MethodCfg(String name, Map<String, String[]> graphDict, String inDegrees, String outDegrees, Map<String, Object> otherGraphInfo) {
        this.name = name;
        this.graphDict = graphDict;
        this.inDegrees = inDegrees;
        this.outDegrees = outDegrees;
        this.otherGraphInfo = otherGraphInfo;
    }

    public String getName() {
        return name;
    }

    public Map<String, String[]> getGraphDict() {
        return graphDict;
    }
}

