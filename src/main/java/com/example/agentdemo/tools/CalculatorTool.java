package com.example.agentdemo.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.example.agentdemo.agent.Tool;

/**
 * 计算器工具。
 *
 * 为什么自己写表达式解析，而不用脚本引擎 eval：
 * 输入是**模型给的字符串**，交给 JS 引擎/反射去执行，等于把任意代码执行权交给模型
 * ——这是 Agent 安全的头号红线（提示词注入 → 工具参数注入 → 代码执行）。
 * 所以只实现数字和 + - * / ( )，多一个字符都算非法。
 */
@Component
public class CalculatorTool implements Tool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算数学表达式，支持 + - * / 和括号，例如 (1200*0.8)+50。输入必须是纯表达式，不能带文字";
    }

    @Override
    public String run(String input) {
        String expression = input.replace("=", "").replace("，", "").trim();
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("表达式为空");
        }
        double value = new Parser(expression).parse();
        return expression + " = " + prettify(value);
    }

    /** 去掉浮点尾巴：0.1+0.2 会算出 0.30000000000000004，显示成 0.3 才不会吓到人 */
    private String prettify(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new ArithmeticException("结果不是有效数字（除零？）");
        }
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** 递归下降解析：expr := term (('+'|'-') term)* ；term := factor (('*'|'/') factor)* */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double v = expr();
            skipSpaces();
            if (pos < s.length()) {
                throw new IllegalArgumentException("表达式里有解析不了的内容：" + s.substring(pos));
            }
            return v;
        }

        private double expr() {
            double left = term();
            while (true) {
                skipSpaces();
                if (eat('+')) left += term();
                else if (eat('-')) left -= term();
                else return left;
            }
        }

        private double term() {
            double left = factor();
            while (true) {
                skipSpaces();
                if (eat('*')) left *= factor();
                else if (eat('/')) left /= factor();
                else return left;
            }
        }

        private double factor() {
            skipSpaces();
            if (eat('-')) return -factor();
            if (eat('(')) {
                double v = expr();
                if (!eat(')')) throw new IllegalArgumentException("括号没配对");
                return v;
            }
            return number();
        }

        private double number() {
            skipSpaces();
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("这里应该是个数字：" + s.substring(pos));
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        private boolean eat(char c) {
            skipSpaces();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
