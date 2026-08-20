package com.kb.kbplatform.service;

import org.springframework.stereotype.Service;

/**
 * AI 问答（RAG）预留服务。
 *
 * 当前工程未引入 Spring AI 依赖（Spring AI 2.0.0 尚无正式版，仅有 -Mx 里程碑，会导致依赖解析失败），
 * 故这里仅作占位：始终返回“尚未启用”提示。待后续接入模型与向量库时，在 backend/pom.xml 引入
 * Spring AI 正式版，并将本类替换为基于 ChatClient + QuestionAnswerAdvisor 的真实 RAG 实现。
 * 详见 README「启用 AI 能力」。
 */
@Service
public class RagService {

    public String ask(String question) {
        return "AI 问答尚未启用。请先在 backend/pom.xml 引入 Spring AI 正式版依赖、配置好模型与向量库，"
                + "再将本服务替换为真实 RAG 实现。当前阶段可正常使用「搜索框 + 检索」。";
    }
}
