package com.anomalyco.ifinbrain.web.rag.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 把抽取出的纯文本切成适合 embedding 的片段。
 *
 * <p>切分策略：先按「段落 → 句子」两级边界拆成原子句，再按目标长度贪心合并。
 * 直接按固定字数硬切会把一句话腰斩，embedding 出来的向量语义不完整，
 * 检索时也更容易答非所问。
 *
 * <p>相邻块保留 {@code overlap} 字符的重叠：答案如果正好跨在两块边界上，
 * 没有重叠就会丢掉跨越处的上下文。
 */
public final class TextChunker {

    /** 句子终止符。中英文都覆盖，句末标点比空格更适合作为切点。 */
    private static final String SENTENCE_ENDERS = "。！？；!?;\n";

    private TextChunker() {
    }

    /**
     * @param text    原始文本，可含大量空白
     * @param size    目标块长度（字符数）
     * @param overlap 相邻块重叠字符数，会被夹到 {@code [0, size/2)} 以防块几乎完全重复
     * @return 切好的文本块；输入为空时返回空列表
     */
    public static List<String> split(String text, int size, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (size <= 0) {
            throw new IllegalArgumentException("chunk size 必须 > 0，实际 " + size);
        }
        // overlap 夹到 size/2 以内：再大相邻块就几乎重复，embedding 成本白翻一倍，
        // 检索结果里也会出现内容雷同的"多条证据"。
        int effOverlap = Math.min(Math.max(overlap, 0), size / 2);
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder(size + 64);
        for (String sentence : splitSentences(normalized)) {
            // 超长句（无标点的整段表格/代码）单独成块，硬切
            if (sentence.length() > size) {
                flush(out, cur);
                for (int i = 0; i < sentence.length(); i += size) {
                    out.add(sentence.substring(i, Math.min(i + size, sentence.length())));
                }
                continue;
            }
            if (cur.length() + sentence.length() > size && cur.length() > 0) {
                out.add(cur.toString());
                // 下一块以本块尾部 overlap 个字符起头，保留跨块语义
                cur = new StringBuilder(size + 64).append(tail(out.get(out.size() - 1), effOverlap));
            }
            cur.append(sentence);
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    /** 取块尾 overlap 个字符作为下一块前缀。 */
    private static String tail(String chunk, int overlap) {
        if (overlap <= 0 || chunk.length() <= overlap) {
            return "";
        }
        return chunk.substring(chunk.length() - overlap);
    }

    /** 两级切分：先空行分段，再在段内按句末标点切句。 */
    private static List<String> splitSentences(String s) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\\n{2,}")) {
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char c = para.charAt(i);
                cur.append(c);
                if (SENTENCE_ENDERS.indexOf(c) >= 0) {
                    addIfNotBlank(out, cur.toString());
                    cur.setLength(0);
                }
            }
            addIfNotBlank(out, cur.toString());
        }
        return out;
    }

    private static void addIfNotBlank(List<String> out, String s) {
        String t = s.strip();
        if (!t.isEmpty()) {
            out.add(t);
        }
    }

    /** 压缩连续空白为单空格，保留换行（段落边界要留着）。 */
    private static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append(c);
                prevSpace = true;
            } else if (Character.isWhitespace(c)) {
                if (!prevSpace) sb.append(' ');
                prevSpace = true;
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString().strip();
    }
}
