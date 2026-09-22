package io.legado.app.model.document.pdf

/**
 * 行间自动换行判定规则。
 *
 * 判定「上一行行尾」与「下一行行首」是否属于同一句的自动折行。
 */
object LineMergeRules {

    /** 行末出现即判定为人工换行的字符（标点、括号、引号、连接符） */
    private const val LINE_END_BREAKERS = "。！？!?；;，,、：:）)】]》>」』\"”'’…—-～~／/\\|"

    /** 行首出现即判定为新单元开始的字符（标点、项目符号、序号标记） */
    private const val LINE_START_BREAKERS =
        "。！？!?；;，,、：:（(【[《<「『\"“'‘•·◦▪▸●○※§#*+-—…" +
        "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳" +
        "⒈⒉⒊⒋⒌⒍⒎⒏⒐⒑" +
        "ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ"

    /** 行首形如 1. 1) (1) 一、 IV. 的序号前缀 */
    private val LINE_START_ORDINAL = Regex(
        r"""^(?:\(?\d+[.)、．]|[一二三四五六七八九十百千]+[、.．)]|[IVXLC]+[.)、])"""
    )

    /**
     * 五条判据全部满足才允许合并（任一不满足即断句）
     */
    fun canMergeLines(
        prevRight: Float,
        prevLeft: Float,
        blockRight: Float,
        nextLeft: Float,
        blockLeft: Float,
        charW: Float,
        prevLastChar: String,
        nextFirstChar: String,
        prevLineText: String = "",
        nextLineText: String = "",
        endSlackChars: Float = 2.0f,
        indentSlackChars: Float = 0.5f,
    ): Boolean {
        if (prevLastChar.isEmpty() || nextFirstChar.isEmpty()) return false
        val w = if (charW > 0) charW else 1.0f

        // 1) 行末必须贴近版心右边界（排不下才折行）
        if (blockRight - prevRight > endSlackChars * w) return false

        // 2) 行末不得有任何标点/符号
        if (LINE_END_BREAKERS.contains(prevLastChar)) return false

        // 3) 下一行不得有缩进
        if (nextLeft - blockLeft > indentSlackChars * w) return false

        // 4) 下一行行首不得是标点、项目符号或序号
        if (LINE_START_BREAKERS.contains(nextFirstChar)) return false
        if (nextLineText.isNotEmpty() && LINE_START_ORDINAL.containsMatchIn(nextLineText)) {
            return false
        }

        // 5) 跨列/网格布局检测：下一行起于上一行起点的左侧
        if (nextLeft < prevLeft - 0.5f * w) return false

        // 6) 同行连续 2 空格 -> 不合并（多列/表格布局特征）
        if (prevLineText.contains("  ") || nextLineText.contains("  ")) return false

        return true
    }

    /** 跨行拼接时是否需要补一个空格（西文补空格，CJK 不补） */
    fun needsSpaceBetween(prevLastChar: String, nextFirstChar: String): Boolean {
        return !isCjk(prevLastChar) && !isCjk(nextFirstChar)
    }

    /** 首字符是否为 CJK（含中日韩统一表意文字、兼容表意文字、中文标点、全角符号） */
    fun isCjk(s: String): Boolean {
        if (s.isEmpty()) return false
        val cp = s.codePointAt(0)
        return (cp in 0x3000..0x303F) || // CJK 标点
                (cp in 0x3400..0x4DBF) || // 扩展 A
                (cp in 0x4E00..0x9FFF) || // 基本区
                (cp in 0xF900..0xFAFF) || // 兼容表意
                (cp in 0xFF00..0xFFEF)    // 全角
    }
}
