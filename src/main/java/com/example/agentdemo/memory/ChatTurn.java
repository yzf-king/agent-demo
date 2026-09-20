package com.example.agentdemo.memory;

/**
 * 一轮对话。用 role 区分说话人（user / assistant），不是靠文字拼"用户说：..."。
 *
 * 为什么不把历史拼成一个大字符串：模型分不清"哪些是用户说的、哪些是自己上次说的"，
 * 容易把上一轮自己的错误答案当成事实接着往下编。角色是结构化信息，别丢。
 */
public record ChatTurn(String role, String content) {

    public static ChatTurn user(String content) {
        return new ChatTurn("user", content);
    }

    public static ChatTurn assistant(String content) {
        return new ChatTurn("assistant", content);
    }
}
