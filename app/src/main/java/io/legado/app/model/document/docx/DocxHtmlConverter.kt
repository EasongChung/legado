package io.legado.app.model.document.docx

import org.w3c.dom.Element
import org.w3c.dom.Node
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Word (.docx) 单页数据结构
 */
data class DocxPageData(
    val pageIndex: Int,
    val title: String,
    val htmlBody: String,
    val sentences: List<String>
)

data class DocxDocumentData(
    val pages: List<DocxPageData>
)

data class DocxResult(
    val html: String,
    val sentences: List<String>
)

/**
 * Word (.docx) -> HTML 转换器（单页原文流式排版与精准高亮朗读增强）。
 *
 * 将 .docx 还原为内联样式的轻量 HTML，用于单页原文渲染。
 * 覆盖：段落对齐/首行缩进/行距、加粗/斜体/下划线/字号/颜色、本地文件图片、表格。
 */
object DocxHtmlConverter {

    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /**
     * 将 docx 文件转成多页结构，每页拥有独立的 HTML body 和句子列表
     */
    fun convertToPages(filePath: String): DocxDocumentData {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Word 文件不存在: $filePath")
        }

        val zip = ZipFile(file)
        try {
            // 1. 扫描图片资源: word/media/* -> 缓存至本地磁盘，使用 file:// URI，彻底杜绝 Base64 造成的 OOM
            val mediaMap = mutableMapOf<String, String>()
            val cacheFolder = File(appCtx.cacheDir, "docx_media/${file.nameWithoutExtension}").apply { mkdirs() }
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("word/media/")) {
                    val name = entry.name.substring("word/media/".length)
                    val targetFile = File(cacheFolder, name)
                    if (!targetFile.exists() || targetFile.length() == 0L) {
                        try {
                            zip.getInputStream(entry).use { inStream ->
                                FileOutputStream(targetFile).use { outStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    mediaMap[name] = targetFile.toURI().toString()
                }
            }

            // 2. 读取 word/document.xml
            val docEntry: ZipEntry? = zip.getEntry("word/document.xml")
            if (docEntry == null) {
                throw IllegalStateException("docx 缺少 word/document.xml，文件可能已损坏")
            }

            val docStream: InputStream = zip.getInputStream(docEntry)
            val dbFactory = DocumentBuilderFactory.newInstance()
            dbFactory.isNamespaceAware = true
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(docStream)
            doc.documentElement.normalize()

            val pages = mutableListOf<DocxPageData>()
            val bodyList = doc.getElementsByTagNameNS(NS_W, "body")

            var curPageIndex = 0
            var curTitle = "第 1 页"
            val curBody = StringBuilder()
            val curSentences = mutableListOf<String>()
            var curCharCount = 0

            fun commitPage(nextTitle: String) {
                val htmlStr = curBody.toString().trim()
                if (htmlStr.isNotEmpty() || curPageIndex == 0) {
                    pages.add(
                        DocxPageData(
                            pageIndex = curPageIndex,
                            title = curTitle,
                            htmlBody = htmlStr.ifEmpty { "<p>（空页）</p>" },
                            sentences = ArrayList(curSentences)
                        )
                    )
                    curPageIndex++
                }
                curBody.clear()
                curSentences.clear()
                curCharCount = 0
                curTitle = nextTitle
            }

            if (bodyList.length > 0) {
                val body = bodyList.item(0) as Element
                val children = body.childNodes

                for (i in 0 until children.length) {
                    val child = children.item(i)
                    if (child.nodeType != Node.ELEMENT_NODE) continue
                    val element = child as Element

                    val hasPageBreak = checkPageBreak(element)
                    val isHeading = checkHeading(element)
                    val blockText = element.textContent?.trim() ?: ""

                    // 1. 显式分页符
                    if (hasPageBreak && curBody.isNotEmpty()) {
                        commitPage("第 ${curPageIndex + 1} 页")
                    } else if (isHeading && blockText.isNotBlank() && curBody.isNotEmpty()) {
                        commitPage(blockText.take(20))
                    }

                    val blockHtml = elementToHtml(element, mediaMap, curSentences)
                    if (blockHtml.isNotEmpty()) {
                        curBody.append(blockHtml)
                        curCharCount += blockText.length

                        // 2. 长文档自然适读分页（每 1200 字符切分为一页，兼顾单页排版体验）
                        if (curCharCount >= 1200) {
                            commitPage("第 ${curPageIndex + 1} 页")
                        }
                    }
                }
            }

            // 提交最后一页
            if (curBody.isNotEmpty() || pages.isEmpty()) {
                pages.add(
                    DocxPageData(
                        pageIndex = curPageIndex,
                        title = curTitle,
                        htmlBody = curBody.toString().ifEmpty { "<p>（文档内容为空）</p>" },
                        sentences = ArrayList(curSentences)
                    )
                )
            }

            return DocxDocumentData(pages)
        } finally {
            try { zip.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 根据当前阅读主题组装完整的 HTML 页面
     */
    fun buildPageHtml(
        bodyHtml: String,
        bgColorHex: String,
        textColorHex: String,
        isNight: Boolean
    ): String {
        val tableBorderColor = if (isNight) "#333333" else "#D0D0D0"
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                <style>
                    * { box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                        margin: 16px;
                        font-size: 17px;
                        line-height: 1.8;
                        color: $textColorHex;
                        background-color: $bgColorHex;
                        word-break: break-word;
                        -webkit-tap-highlight-color: transparent;
                        user-select: none;
                    }
                    table {
                        border-collapse: collapse;
                        width: 100%;
                        margin: 12px 0;
                    }
                    td, th {
                        border: 1px solid $tableBorderColor;
                        padding: 8px 10px;
                        vertical-align: top;
                    }
                    img {
                        max-width: 100%;
                        height: auto;
                        display: block;
                        margin: 12px auto;
                        border-radius: 4px;
                    }
                    p {
                        margin: 8px 0;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        margin: 14px 0 8px 0;
                        font-weight: bold;
                        line-height: 1.4;
                    }
                    .wm-hl {
                        background: rgba(255, 167, 38, 0.38) !important;
                        border-radius: 4px;
                        transition: background 0.2s ease;
                    }
                </style>
            </head>
            <body>
                $bodyHtml
                <script>
                    function highlightIndex(index) {
                        var prev = document.querySelectorAll('.wm-hl');
                        for (var i = 0; i < prev.length; i++) {
                            prev[i].classList.remove('wm-hl');
                        }
                        var el = document.querySelector('[data-idx="' + index + '"]');
                        if (el) {
                            el.classList.add('wm-hl');
                            var rect = el.getBoundingClientRect();
                            var vh = window.innerHeight || document.documentElement.clientHeight;
                            if (rect.top < 20 || rect.bottom > (vh - 30)) {
                                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            }
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * 兼容接口：单流转换
     */
    fun convert(filePath: String): DocxResult {
        val docData = convertToPages(filePath)
        val allSentences = mutableListOf<String>()
        val allHtml = StringBuilder()
        docData.pages.forEach { page ->
            allSentences.addAll(page.sentences)
            allHtml.append(page.htmlBody)
        }
        val fullHtml = buildPageHtml(allHtml.toString(), "#FFFFFF", "#2C3E50", false)
        return DocxResult(fullHtml, allSentences)
    }

    private fun checkPageBreak(el: Element): Boolean {
        if (el.localName == "p") {
            val brs = el.getElementsByTagNameNS(NS_W, "br")
            for (i in 0 until brs.length) {
                val br = brs.item(i) as Element
                val type = br.getAttributeNS(NS_W, "type").ifEmpty { br.getAttribute("type") }
                if (type == "page") return true
            }
            if (el.getElementsByTagNameNS(NS_W, "lastRenderedPageBreak").length > 0) return true
            val pPr = getFirstChild(el, "pPr")
            if (pPr != null && getFirstChild(pPr, "sectPr") != null) return true
        }
        return false
    }

    private fun checkHeading(el: Element): Boolean {
        if (el.localName == "p") {
            val pPr = getFirstChild(el, "pPr") ?: return false
            val pStyle = getFirstChild(pPr, "pStyle") ?: return false
            val styleVal = pStyle.getAttributeNS(NS_W, "val").ifEmpty { pStyle.getAttribute("val") }.lowercase()
            return styleVal.contains("heading") || styleVal.contains("标题") || styleVal.contains("title")
        }
        return false
    }

    private fun elementToHtml(el: Element, mediaMap: Map<String, String>, sentences: MutableList<String>): String {
        return when (el.localName) {
            "p" -> paragraphToHtml(el, mediaMap, sentences)
            "tbl" -> tableToHtml(el, mediaMap, sentences)
            "sectPr" -> ""
            else -> {
                val runs = runsToHtml(el, mediaMap)
                if (runs.isNotEmpty()) {
                    val text = el.textContent?.trim() ?: ""
                    val idxAttr = if (text.isNotBlank()) {
                        val idx = sentences.size
                        sentences.add(text)
                        " data-idx=\"$idx\""
                    } else ""
                    "<p$idxAttr>$runs</p>"
                } else ""
            }
        }
    }

    private fun paragraphToHtml(p: Element, mediaMap: Map<String, String>, sentences: MutableList<String>): String {
        val style = StringBuilder()
        var headingLevel = 0

        val pPr = getFirstChild(p, "pPr")
        if (pPr != null) {
            parseParaStyle(pPr, style)
            val pStyle = getFirstChild(pPr, "pStyle")
            if (pStyle != null) {
                val styleVal = pStyle.getAttributeNS(NS_W, "val").ifEmpty { pStyle.getAttribute("val") }.lowercase()
                if (styleVal.contains("heading") || styleVal.contains("标题") || styleVal.contains("title")) {
                    headingLevel = styleVal.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 6) ?: 1
                }
            }
        }
        val runs = runsToHtml(p, mediaMap)
        if (runs.isEmpty()) return ""
        val text = p.textContent?.trim() ?: ""
        val idxAttr = if (text.isNotBlank()) {
            val idx = sentences.size
            sentences.add(text)
            " data-idx=\"$idx\""
        } else {
            ""
        }
        val styleAttr = if (style.isNotEmpty()) " style=\"${style.trim()}\"" else ""
        return if (headingLevel in 1..6) {
            "<h$headingLevel$idxAttr$styleAttr>$runs</h$headingLevel>"
        } else {
            "<p$idxAttr$styleAttr>$runs</p>"
        }
    }

    private fun tableToHtml(tbl: Element, mediaMap: Map<String, String>, sentences: MutableList<String>): String {
        val rows = StringBuilder()
        val children = tbl.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.localName == "tr") {
                val cells = StringBuilder()
                val trChildren = child.childNodes
                for (j in 0 until trChildren.length) {
                    val tc = trChildren.item(j)
                    if (tc.nodeType == Node.ELEMENT_NODE && tc.localName == "tc") {
                        val cellContent = StringBuilder()
                        val tcChildren = tc.childNodes
                        for (k in 0 until tcChildren.length) {
                            val p = tcChildren.item(k)
                            if (p.nodeType == Node.ELEMENT_NODE && p.localName == "p") {
                                cellContent.append(paragraphToHtml(p as Element, mediaMap, sentences))
                            }
                        }
                        cells.append("<td>$cellContent</td>")
                    }
                }
                rows.append("<tr>$cells</tr>")
            }
        }
        return "<table>$rows</table>"
    }

    private fun parseParaStyle(pPr: Element, out: StringBuilder) {
        val jc = getFirstChild(pPr, "jc")
        if (jc != null) {
            val v = jc.getAttributeNS(NS_W, "val").ifEmpty { jc.getAttribute("val") }
            val align = when (v) {
                "center" -> "center"
                "right" -> "right"
                "both", "distribute" -> "justify"
                else -> "left"
            }
            out.append("text-align:$align;")
        }
        val ind = getFirstChild(pPr, "ind")
        if (ind != null) {
            val firstLine = ind.getAttributeNS(NS_W, "firstLine").ifEmpty { ind.getAttribute("firstLine") }
            if (firstLine.isNotEmpty()) {
                val dxa = firstLine.toIntOrNull() ?: 0
                if (dxa != 0) {
                    out.append("text-indent:${String.format("%.2f", dxa / 240.0)}em;")
                }
            }
        }
        val spacing = getFirstChild(pPr, "spacing")
        if (spacing != null) {
            val line = spacing.getAttributeNS(NS_W, "line").ifEmpty { spacing.getAttribute("line") }
            val lineRule = spacing.getAttributeNS(NS_W, "lineRule").ifEmpty { spacing.getAttribute("lineRule") }
            if (line.isNotEmpty()) {
                val v = line.toIntOrNull() ?: 0
                if (lineRule == "auto") {
                    out.append("line-height:${String.format("%.1f", v / 240.0)};")
                } else if (v > 0) {
                    out.append("line-height:${String.format("%.1f", v / 20.0)}px;")
                }
            }
        }
    }

    private fun runsToHtml(parent: Element, mediaMap: Map<String, String>): String {
        val buf = StringBuilder()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val el = child as Element
            when (el.localName) {
                "r" -> {
                    val rPr = getFirstChild(el, "rPr")
                    val style = StringBuilder()
                    if (rPr != null) parseRunStyle(rPr, style)

                    val textBuf = StringBuilder()
                    val rChildren = el.childNodes
                    for (j in 0 until rChildren.length) {
                        val rc = rChildren.item(j)
                        if (rc.nodeType != Node.ELEMENT_NODE) continue
                        val rcEl = rc as Element
                        when (rcEl.localName) {
                            "t" -> textBuf.append(rcEl.textContent)
                            "br" -> textBuf.append("<br>")
                            "drawing", "pict" -> {
                                val img = inlineImage(rcEl, mediaMap)
                                if (img != null) textBuf.append(img)
                            }
                        }
                    }
                    if (textBuf.isNotEmpty()) {
                        val s = style.toString().trim()
                        if (s.isEmpty()) {
                            buf.append(textBuf)
                        } else {
                            buf.append("<span style=\"$s\">$textBuf</span>")
                        }
                    }
                }
                "hyperlink", "ins", "smartTag" -> {
                    buf.append(runsToHtml(el, mediaMap))
                }
            }
        }
        return buf.toString()
    }

    private fun parseRunStyle(rPr: Element, out: StringBuilder) {
        if (hasChild(rPr, "b")) out.append("font-weight:bold;")
        if (hasChild(rPr, "i")) out.append("font-style:italic;")
        if (hasChild(rPr, "u")) out.append("text-decoration:underline;")
        if (hasChild(rPr, "strike")) out.append("text-decoration:line-through;")

        val sz = getFirstChild(rPr, "sz")
        if (sz != null) {
            val v = sz.getAttributeNS(NS_W, "val").ifEmpty { sz.getAttribute("val") }
            val half = v.toIntOrNull() ?: 0
            if (half > 0) {
                out.append("font-size:${String.format("%.1f", half / 2.0)}pt;")
            }
        }
        val color = getFirstChild(rPr, "color")
        if (color != null) {
            val v = color.getAttributeNS(NS_W, "val").ifEmpty { color.getAttribute("val") }
            if (v.isNotEmpty() && v != "auto") {
                out.append("color:#$v;")
            }
        }
    }

    private fun inlineImage(el: Element, mediaMap: Map<String, String>): String? {
        val blips = el.getElementsByTagNameNS("*", "blip")
        if (blips.length > 0) {
            val blip = blips.item(0) as Element
            var rId = blip.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
            if (rId.isEmpty()) rId = blip.getAttribute("r:embed")
            if (rId.isNotEmpty()) {
                val numMatch = Regex("""(\d+)$""").find(rId)
                val num = numMatch?.groupValues?.get(1)
                var fileUri: String? = null
                if (num != null) {
                    val entry = mediaMap.entries.firstOrNull {
                        it.key.contains("$num.") || it.key.startsWith("image$num")
                    }
                    if (entry != null) fileUri = entry.value
                }
                if (fileUri == null && mediaMap.isNotEmpty()) {
                    fileUri = mediaMap.values.first()
                }
                if (fileUri != null) {
                    return "<img src=\"$fileUri\" />"
                }
            }
        }
        return null
    }

    private fun getFirstChild(parent: Element, localName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.localName == localName) {
                return node as Element
            }
        }
        return null
    }

    private fun hasChild(parent: Element, localName: String): Boolean {
        return getFirstChild(parent, localName) != null
    }
}
