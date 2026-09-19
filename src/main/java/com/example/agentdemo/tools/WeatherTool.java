package com.example.agentdemo.tools;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.agentdemo.agent.Tool;

/**
 * 天气工具（示例数据，不是真实 API）。
 *
 * 故意不接真实天气 API：这个作品的目的是讲清"工具机制"，而不是天气本身。
 * 真实 API 要注册 key、有配额、还会变——但**工具的实现换成 HTTP 调用，Agent 侧一行都不用改**，
 * 因为 Agent 只依赖 name/description/输入输出这三样东西。这就是工具抽象的价值。
 * 返回值里明确写了"示例数据"，避免演示时被误当成真实天气。
 */
@Component
public class WeatherTool implements Tool {

    private static final Map<String, String> MOCK = Map.of(
            "杭州", "晴，26℃，东南风 2 级",
            "北京", "多云，22℃，北风 3 级",
            "上海", "小雨，24℃，东风 2 级",
            "深圳", "阴，29℃，南风 1 级");

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "查询某个城市的天气，输入是城市名，例如 杭州。目前只支持杭州/北京/上海/深圳";
    }

    @Override
    public String run(String input) {
        String city = input.replace("市", "").trim();
        String weather = MOCK.get(city);
        if (weather == null) {
            return "暂时查不到「" + city + "」的天气，本工具目前只支持：" + String.join("、", MOCK.keySet());
        }
        return city + "：" + weather + "（示例数据，非真实天气 API）";
    }
}
