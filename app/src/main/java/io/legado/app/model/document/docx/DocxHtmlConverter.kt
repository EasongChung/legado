package io.legado.app.model.document.docx

import android.util.Base64
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Word (.docx) -> HTML 转换器（流式排版与点读增强）。
 *
 * 将 .docx 文档还原为内联样式的轻量 HTML，用于 WebView 原文渲染。
 * 覆盖：段落对齐/首行缩进/行距、加粗/斜体/下划线/字号/颜色、图片（base64）、表格。
 * 同时注入点读与高亮控制脚本。
 */
data class DocxResult(
    val html: String,
    val sentences: List<String>
)

object DocxHtmlConverter {

    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /**
     * 将 docx 文件转成 HTML 字符串与句子/段落列表
     */
    fun convert(filePath: String): DocxResult {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("Word 文件不存在: $filePath")
        }

        val zip = ZipFile(file)
        try {
            // 1. 扫描图片资源: word/media/* -> base64
            val mediaMap = mutableMapOf<String, String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("word/media/")) {
                    val name = entry.name.substring("word/media/".length)
                    val bytes = zip.getInputStream(entry).readBytes()
                    if (bytes.isNotEmpty()) {
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        mediaMap[name] = base64
                    }
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

            // 3. 构建 HTML
            val sb = StringBuilder()
            val sentences = mutableListOf<String>()
            sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">")
            sb.append("<style>")
            sb.append("body { font-family: -apple-system, sans-serif; margin: 16px; font-size: 17px; line-height: 1.8; color: #2C3E50; background: #FFFFFF; word-break: break-word; -webkit-tap-highlight-color: transparent; }")
            sb.append("table { border-collapse: collapse; width: 100%; margin: 8px 0; }")
            sb.append("td, th { border: 1px solid #BDC3C7; padding: 6px 10px; vertical-align: top; }")
            sb.append("img { max-width: 100%; height: auto; display: block; margin: 8px auto; border-radius: 4px; }")
            sb.append(".wm-hl { background: #FFE0B2 !important; border-radius: 4px; transition: background 0.2s ease; }")
            sb.append("</style></head><body>")

            val bodyList = doc.getElementsByTagNameNS(NS_W, "body")
            var blockCount = 0
            if (bodyList.length > 0) {
                val body = bodyList.item(0) as Element
                val children = body.childNodes
                for (i in 0 until children.length) {
                    val child = children.item(i)
                    if (child.nodeType == Node.ELEMENT_NODE) {
                        val blockHtml = elementToHtml(child as Element, mediaMap, sentences)
                        if (blockHtml.isNotEmpty()) {
                            sb.append(blockHtml)
                            blockCount++
                        }
                    }
                }
            }

            if (blockCount == 0) {
                sb.append("<p>（文档内容为空或无法提取正文排版）</p>")
            }

            // 注入点读交互脚本与高亮API
            sb.append("<script>")
            sb.append("""
                (function() {
                    document.addEventListener('click', function(e) {
                        var el = e.target.closest('[data-idx]');
                        if (!el) {
                            el = e.target.closest('p,td,li,h1,h2,h3,h4,h5,h6');
                        }
                        if (!el) return;
                        var text = (el.innerText || el.textContent || '').trim();
                        if (!text) return;
                        var idxAttr = el.getAttribute('data-idx');
                        var idx = idxAttr ? parseInt(idxAttr, 10) : -1;
                        var prev = document.querySelector('.wm-hl');
                        if (prev) prev.classList.remove('wm-hl');
                        el.classList.add('wm-hl');
                        if (window.DocReadBridge && window.DocReadBridge.onSentenceClick) {
                            window.DocReadBridge.onSentenceClick(text, idx);
                        } else {
                            console.log('DocReadBridge not found on window');
                        }
                    }, true);
                })();
                
                function highlightIndex(index) {
                    var el = document.querySelector('[data-idx="' + index + '"]');
                    if (el) {
                        var prev = document.querySelector('.wm-hl');
                        if (prev) prev.classList.remove('wm-hl');
                        el.classList.add('wm-hl');
                        var rect = el.getBoundingClientRect();
                        var vh = window.innerHeight || document.documentElement.clientHeight;
                        var inView = (rect.top >= 20 && rect.bottom <= (vh - 30));
                        if (!inView) {
                            el.scrollIntoView({ behavior: 'smooth', block: 'start' });
                        }
                    }
                }
            """.trimIndent())
            sb.append("</script>")
            sb.append("</body></html>")

            return DocxResult(sb.toString(), sentences)
        } finally {
            zip.close()
        }
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
        val pPr = getFirstChild(p, "pPr")
        if (pPr != null) {
            parseParaStyle(pPr, style)
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
        return "<p$idxAttr$styleAttr>$runs</p>"
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
                var base64: String? = null
                if (num != null) {
                    val entry = mediaMap.entries.firstOrNull {
                        it.key.contains("$num.") || it.key.startsWith("image$num")
                    }
                    if (entry != null) base64 = entry.value
                }
                if (base64 == null && mediaMap.isNotEmpty()) {
                    base64 = mediaMap.values.first()
                }
                if (base64 != null) {
                    return "<img src=\"data:image/png;base64,$base64\" />"
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
