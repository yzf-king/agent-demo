package com.example.agentdemo.tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.example.agentdemo.agent.Tool;

/**
 * 当前时间。
 * 这类"模型自己不知道、但系统一问就有"的信息，正是工具存在的第一理由：
 * 模型的知识停在训练截止日，今天的日期它只能猜。
 */
@Component
public class CurrentTimeTool implements Tool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public String description() {
        return "获取当前的日期和时间。不需要输入，输入留空即可";
    }

    @Override
    public String run(String input) {
        LocalDateTime now = LocalDateTime.now();
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return "当前时间：" + now.format(FMT) + "（" + weekday + "）";
    }
}
