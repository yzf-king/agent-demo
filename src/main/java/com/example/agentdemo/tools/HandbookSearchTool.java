package com.example.agentdemo.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.example.agentdemo.agent.Tool;

/**
 * 员工手册检索（关键词版，是候选实现，不是最终形态）。
 *
 * 这里没有 embedding、没有向量库，纯字符二元组打分——因为 W3 的主题是 Agent 循环和工具机制，
 * 检索效果好不好不是重点。**它存在的意义是占住"知识检索"这个工具位**：
 * 等到 W4 做作品 2 时，把这个类的 run() 换成调用 rag-demo 的检索接口，
 * Agent 侧（prompt、循环、解析）一行都不用改——工具的价值就在这个接缝上。
 *
 * 打分方式：把问题切成二元组（"远程办公" → 远程/程办/办公），
 * 统计每个小节里出现的次数，标题命中加权。中文不用分词器也能凑合。
 */
@Component
public class HandbookSearchTool implements Tool {

    private static final int TOP_N = 2;
    private static final int MAX_BODY_CHARS = 400;

    private final List<Section> sections;

    public HandbookSearchTool(@Value("classpath:knowledge/handbook.md") Resource resource) {
        try {
            this.sections = parse(resource.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("员工手册加载失败", e);
        }
    }

    @Override
    public String name() {
        return "handbook_search";
    }

    @Override
    public String description() {
        return "检索公司员工手册（试用期、年假、病假、报销差旅、办公远程、培训、加班、绩效、离职、福利等）。"
                + "输入是检索关键词或一句简短的问题，例如 远程办公";
    }

    @Override
    public String run(String input) {
        String query = input.trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("检索关键词为空");
        }
        if (sections.isEmpty()) {
            return "手册内容为空";
        }
        List<String> bigrams = bigrams(query);

        List<Section> hits = sections.stream()
                .map(s -> new Object[]{s, score(s, bigrams)})
                .sorted(Comparator.comparingDouble(o -> -((double) o[1])))
                .limit(TOP_N)
                .map(o -> (Section) o[0])
                .filter(s -> score(s, bigrams) > 0)
                .toList();

        if (hits.isEmpty()) {
            // 检索不到时给出目录，让模型知道"手册里确实没有"，而不是瞎猜
            return "没有找到与「" + query + "」相关的小节。手册包含这些章节：\n" + titles();
        }

        StringBuilder sb = new StringBuilder();
        for (Section s : hits) {
            sb.append("【").append(s.title()).append("】\n");
            String body = s.body().length() > MAX_BODY_CHARS
                    ? s.body().substring(0, MAX_BODY_CHARS) + "…" : s.body();
            sb.append(body).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String titles() {
        StringBuilder sb = new StringBuilder();
        for (Section s : sections) {
            sb.append("- ").append(s.title()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 二元组：中文没有空格，用相邻两字当特征；标题权重 3 倍（标题基本就是主题词） */
    private double score(Section section, List<String> bigrams) {
        String body = section.body();
        String title = section.title();
        double score = 0;
        for (String bg : bigrams) {
            score += count(body, bg) + count(title, bg) * 3;
        }
        return score;
    }

    private int count(String text, String needle) {
        int n = 0, from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            n++;
            from += needle.length();
        }
        return n;
    }

    private List<String> bigrams(String query) {
        String cleaned = query.replaceAll("[\\s　，。？！、：；（）()【】\\[\\]]", "");
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            out.add(cleaned.substring(i, i + 2));
        }
        if (out.isEmpty()) out.add(cleaned);   // 单字查询
        return out;
    }

    /** 把 markdown 按 `## ` 切成小节：title = 标题行，body = 标题到下一个标题之间的正文 */
    private List<Section> parse(String markdown) {
        List<Section> out = new ArrayList<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\n")) {
            if (line.startsWith("## ")) {
                if (title != null) out.add(new Section(title, body.toString().trim()));
                title = line.substring(3).trim();
                body.setLength(0);
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        if (title != null) out.add(new Section(title, body.toString().trim()));
        return out;
    }

    private record Section(String title, String body) {
    }
}
