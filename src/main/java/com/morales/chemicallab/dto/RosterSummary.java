package com.morales.chemicallab.dto;
import java.util.List;
public record RosterSummary(long total,long active,List<Group> groups) {
    public record Group(String grade,String section){}
}
