package io.legado.app.model.document.matcher

/**
 * 连续朗读时按阅读顺序匹配当前句在几何候选列表中的索引。
 *
 * 移植自 WiseMuse HANDOVER_SENTENCE_HIGHLIGHT_FIX.md 标准算法：
 * 1. 解决**有相同语句**问题：避免无状态全局匹配永远命中首个相同句，利用 [lastMatchedIndex] 优先单调向后匹配；
 * 2. 解决**包含代码或特殊短符号**问题：严格分层（完全相等 -> 空白规范化精确相等 -> 紧凑无空白完全匹配 -> 长句高重合包含），
 *    严禁短代码/符号双向穿透误匹配；
 * 3. 安全未命中保护：未找到可靠匹配时返回 -1，调用侧安全清空高亮，坚决不绘制错位框。
 */
object SentenceMatcher {

    /**
     * 在候选列表中匹配当前目标文本的下标
     *
     * @param candidates 页面几何单元列表（如 SentenceBox 列表）
     * @param textOf 获取候选单元文本的函数
     * @param targetText 当前朗读的目标文本
     * @param lastMatchedIndex 上一次匹配命中的下标（游标），初始值为 -1
     * @return 匹配命中的索引；未命中返回 -1
     */
    fun <T> matchSentenceIndex(
        candidates: List<T>,
        textOf: (T) -> String,
        targetText: String,
        lastMatchedIndex: Int = -1
    ): Int {
        if (candidates.isEmpty()) return -1
        val targetClean = targetText.trim()
        if (targetClean.isEmpty()) return -1

        val targetNorm = targetClean.replace(Regex("""\s+"""), " ")
        val targetCompact = targetClean.replace(Regex("""\s+"""), "")

        // 1. 确定搜索区间：优先从游标之后检索未朗读区域
        val startIndex = if (lastMatchedIndex in 0 until candidates.size - 1) {
            lastMatchedIndex + 1
        } else {
            0
        }

        // 辅助函数：在指定索引范围内按优先级查找
        fun findInRange(start: Int, end: Int): Int {
            if (start > end || start >= candidates.size) return -1

            // Level 1: 严格精确匹配 (首尾去空完全相等)
            for (i in start..end) {
                val text = textOf(candidates[i]).trim()
                if (text == targetClean) return i
            }

            // Level 2: 空白规范化精确匹配 (消除连续空白与换行差异)
            for (i in start..end) {
                val text = textOf(candidates[i]).trim()
                val norm = text.replace(Regex("""\s+"""), " ")
                if (norm == targetNorm) return i
            }

            // Level 2.5: 紧凑无空白完全匹配 (应对代码缩进差异：Tab、空格数不同等)
            if (targetCompact.isNotEmpty()) {
                for (i in start..end) {
                    val text = textOf(candidates[i])
                    val compact = text.replace(Regex("""\s+"""), "")
                    if (compact == targetCompact) return i
                }
            }

            // Level 3: 高置信前缀匹配 (当分句因分号/注释存在微小差异时)
            // 保护：紧凑长度至少达到 4 字符，防止如 "if"、"{" 等单个词根/符号误判
            if (targetCompact.length >= 4) {
                for (i in start..end) {
                    val text = textOf(candidates[i]).trim()
                    val norm = text.replace(Regex("""\s+"""), " ")
                    val compact = text.replace(Regex("""\s+"""), "")
                    if (compact.length >= 4) {
                        if (norm.startsWith(targetNorm) || targetNorm.startsWith(norm)) {
                            return i
                        }
                    }
                }
            }

            // Level 4: 长文本受控包含匹配 (双向包含时必须满足实质重合度)
            // 保护：较短方紧凑长度 >= 6，且长度占比 >= 50%，彻底杜绝短代码/符号穿透
            if (targetCompact.length >= 6) {
                for (i in start..end) {
                    val text = textOf(candidates[i])
                    val compact = text.replace(Regex("""\s+"""), "")
                    if (compact.length >= 6) {
                        if (compact.contains(targetCompact)) {
                            if (targetCompact.length.toFloat() / compact.length >= 0.5f) return i
                        } else if (targetCompact.contains(compact)) {
                            if (compact.length.toFloat() / targetCompact.length >= 0.5f) return i
                        }
                    }
                }
            }

            return -1
        }

        // 优先在游标之后的未读区间内搜索
        val forwardHit = findInRange(startIndex, candidates.size - 1)
        if (forwardHit != -1) return forwardHit

        // 若游标为初值 -1 且未找到，已搜索全页，无需回溯。
        // 若连读进行中（lastMatchedIndex >= 0），严格禁止回退到已读区域，防止相同语句回跳！
        return -1
    }

    /**
     * 预热快进（Fast-Forward）：若从中间句 (start > 0) 触发播放，先按序把游标推进到 start 之前
     * 保证即使 start 处的句子在前面出现过，游标也能跳过前面的同名句，精准对齐当前句子。
     */
    fun <T> fastForwardCursor(
        candidates: List<T>,
        textOf: (T) -> String,
        targetSentences: List<String>,
        start: Int
    ): Int {
        if (start <= 0 || candidates.isEmpty()) return -1
        var cursor = -1
        for (k in 0 until minOf(start, targetSentences.size)) {
            val idx = matchSentenceIndex(
                candidates = candidates,
                textOf = textOf,
                targetText = targetSentences[k],
                lastMatchedIndex = cursor
            )
            if (idx != -1) cursor = idx
        }
        return cursor
    }
}
