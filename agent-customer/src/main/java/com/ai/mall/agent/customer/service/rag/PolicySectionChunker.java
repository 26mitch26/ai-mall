package com.ai.mall.agent.customer.service.rag;

import java.util.ArrayList;
import java.util.List;

/** Structural context from source headings; whole policy paragraphs and attached exceptions stay together. */
public final class PolicySectionChunker {
    private PolicySectionChunker() {}
    private static final java.util.regex.Pattern QUALIFIER = java.util.regex.Pattern.compile(
            "^(?:但|但是|不过|例外|除外|限制|不适用|不包括|注意|否则|以下.{0,12}不|Exceptions?\\b|However\\b|Except\\b).*",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    /**
     * 触发分块的最高标题级别：H1/H2 结束一个聚合节，H3/H4 等小节标题只更新面包屑路径，
     * 与兄弟小节按软预算聚合（对齐主流实践：结构分块聚合到 256~512 tokens，避免每小节
     * 一块造成 50~150 字符碎片，检索时上下文不足）。
     */
    private static final int MAX_FLUSH_HEADING_LEVEL = 2;
    public static List<String> chunk(String text, int targetCharacters) {
        if(text==null || text.isBlank()) return List.of();
        int target=targetCharacters>0?targetCharacters:512;
        List<String> result=new ArrayList<>(), headings=new ArrayList<>(), units=new ArrayList<>();
        boolean fenced=false;
        boolean gap=true;
        StringBuilder fence=new StringBuilder();
        for(String raw:text.split("\\R",-1)) {
            String line=raw.trim();
            if(line.startsWith("```")) {
                if(fenced) { fence.append(raw); units.add(fence.toString()); fence.setLength(0); fenced=false; }
                else { fenced=true; fence.append(raw).append('\n'); }
                continue;
            }
            if(fenced) { fence.append(raw).append('\n'); continue; }
            if(line.matches("#{1,6}\\s+.+")) {
                int level=headingLevel(line);
                if(level<=MAX_FLUSH_HEADING_LEVEL) { flush(result,headings,units,target); units.clear(); }
                headings.removeIf(h -> headingLevel(h)>=level);
                headings.add(line); gap=true;
            } else if(!line.isBlank()) {
                addUnit(units,line,!gap); gap=false;
            } else gap=true;
        }
        if(fence.length()>0) units.add(fence.toString().stripTrailing());
        flush(result,headings,units,target);
        return List.copyOf(result);
    }
    private static int headingLevel(String text) { int i=0; while(i<text.length() && text.charAt(i)=='#') i++; return i; }
    /** Used by the answer extractor too, so protected exceptions are not lost after retrieval. */
    public static List<String> evidenceUnits(String text) {
        if(text==null || text.isBlank()) return List.of();
        List<String> units=new ArrayList<>();
        boolean gap=true;
        for(String line:text.split("\\R")) {
            if(line.isBlank()) { gap=true; continue; }
            addUnit(units,line.trim(),!gap); gap=false;
        }
        return List.copyOf(units);
    }
    private static void addUnit(List<String> units,String line,boolean adjacent) {
        boolean previousHeading=!units.isEmpty() && units.get(units.size()-1).startsWith("#");
        boolean qualifier=QUALIFIER.matcher(line).matches();
        boolean table=line.startsWith("|") && !units.isEmpty() && units.get(units.size()-1).stripTrailing().endsWith("|");
        boolean continuation=adjacent && !units.isEmpty() && !previousHeading && !line.startsWith("#")
                && !line.matches("^(?:[-*+]\\s|\\d+[.)、]\\s?).*") && !line.startsWith("|")
                && !units.get(units.size()-1).stripTrailing().matches("(?s).*[。！？.!?；;）)」』|`]$");
        if((qualifier || table || continuation) && !units.isEmpty() && !previousHeading) {
            int last=units.size()-1; units.set(last,units.get(last)+"\n"+line);
        } else units.add(line);
    }
    private static void flush(List<String> output,List<String> headings,List<String> units,int target) {
        if(units.isEmpty()) return;
        String context=String.join("\n",headings);
        StringBuilder block=new StringBuilder();
        for(String unit:units) {
            if(block.length()>0 && context.length()+1+block.length()+1+unit.length()>target) {
                output.add(withContext(context,block.toString())); block.setLength(0);
            }
            if(block.length()>0) block.append('\n');
            block.append(unit); // Soft limit: never cut a policy paragraph or its exception to fit a character count.
        }
        if(block.length()>0) output.add(withContext(context,block.toString()));
    }
    private static String withContext(String context,String content) { return context.isBlank()?content:context+"\n"+content; }
}
