package com.ai.mall.agent.customer.rag;

import java.util.List;

/**
 * 中文分词（与生产 RagService.tokenize 完全一致）
 * <p>
 * 英文单词（长度>=2）按空白切分；中文按 bigram（相邻两字）切分。
 * 生产与评测共用同一套切分语义，保证评测结果对生产有参考意义。
 */
final class RagTokenizer {

    private RagTokenizer() {
    }

    static List<String> tokenize(String text) {
        List<String> terms = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String[] words = text.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5\\s]", " ")
                .split("\\s+");
        for (String word : words) {
            if (word.length() >= 2) {
                terms.add(word);
            }
        }
        String chinese = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < chinese.length() - 1; i++) {
            terms.add(chinese.substring(i, i + 2));
        }
        return terms;
    }
}