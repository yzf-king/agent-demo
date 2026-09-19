package com.example.agentdemo.tools;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.agentdemo.agent.Tool;

/**
 * 知识检索工具：调用 rag-demo（作品 1）的只读检索接口 POST /api/knowledge/search。
 *
 * 这是两个作品的分界线，也是这版最有价值的一处设计：
 *   作品 1 = 知识服务（切分 / 向量化 / 检索，是"能力"）
 *   作品 2 = 编排服务（决定什么时候调、怎么用结果，是"决策"）
 * 拆开之后，检索参数、embedding 模型、向量库换什么，Agent 侧一行都不用动——
 * 和 W3 那个"天气工具换成真 API 也不用改 Agent"是同一个道理，只是这次换的是自己写的服务。
 *
 * 上一版这里是本地关键词检索（HandbookSearchTool），已被本类取代（git 历史里还在）：
 * 从"关键词能跑通"到"换成真 RAG"，Agent 侧的 prompt、循环、解析确实一行没改，
 * 这正是工具抽象该有的样子。
 */
@Component
public class KnowledgeSearchTool implements Tool {

    /** 检索回几块。给太多会稀释注意力（作品 1 的实测结论：k=3→5 只增噪音） */
    private static final int TOP_K = 3;

    private final RestClient restClient;
    private final String baseUrl;

    public KnowledgeSearchTool(@Value("${agent.knowledge.base-url:http://localhost:8081}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String description() {
        return "检索公司知识库（员工手册等内部文档），返回最相关的原文片段。"
                + "输入是一句检索问题或关键词，例如 远程办公每周几天";
    }

    @Override
    public String run(String input) {
        List<Hit> hits;
        try {
            hits = restClient.post()
                    .uri("/api/knowledge/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("question", input, "topK", TOP_K))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Hit>>() {
                    });
        } catch (RestClientException e) {
            // 工具不可用也要好好说话：Agent 收到这句会告诉用户"服务没起"，而不是编答案
            return "知识库服务不可用（" + baseUrl + "）：" + e.getMessage()
                    + "。请先启动 rag-demo（作品 1）。";
        }
        if (hits == null || hits.isEmpty()) {
            return "知识库为空，或没有检索到与「" + input + "」相关的内容。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            sb.append("【片段 ").append(i + 1).append("｜相似度 ")
                    .append(h.score() == null ? "?" : String.format("%.3f", h.score()))
                    .append("】\n").append(h.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 与 rag-demo 的 RagService.Hit 字段对应（跨服务靠 JSON 契约，不是同一个类） */
    public record Hit(String text, Double score) {
    }
}
