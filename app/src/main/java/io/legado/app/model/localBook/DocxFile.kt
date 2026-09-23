package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.FileUtils
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.printOnDebug
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Word (.docx) 本地书籍解析器。
 *
 * 深度融入 Legado 原生阅读引擎：
 * 1. 智能按页码（分页符/分节符）、标题大纲（Heading）或标准篇幅自动切分生成多章节；
 * 2. 章节内容转换为标准富文本（保留段落、标题、排版、表格与图片），交由 Legado 原生 Canvas 排版渲染；
 * 3. 完美继承用户所选的任意 Legado 阅读背景（羊皮纸、护眼绿、夜间等）与翻页模式（仿真/覆盖/滑动/无动画）；
 * 4. 支持从 word/media/ 解压提取内嵌图片与生成书籍封面。
 */
class DocxFile(var book: Book) {

    companion object : BaseLocalBookParse {
        private var dFile: DocxFile? = null

        @Synchronized
        private fun getDFile(book: Book): DocxFile {
            if (dFile == null || dFile?.book?.bookUrl != book.bookUrl) {
                dFile = DocxFile(book)
                return dFile!!
            }
            dFile?.book = book
            return dFile!!
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getDFile(book).upBookInfo()
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getDFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getDFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getDFile(book).getImage(href)
        }
    }

    private val chapters = ArrayList<BookChapter>()
    private val chapterContents = HashMap<Int, String>()
    private var isParsed = false

    private fun getDocxFile(): File? {
        return try {
            val uri = book.getLocalUri()
            if (uri.isContentScheme()) {
                BookHelp.getLocalOrCachedFile(book)
            } else {
                File(uri.path ?: book.bookUrl)
            }
        } catch (e: Exception) {
            AppLog.put("获取 Docx 文件失败", e)
            null
        }
    }

    private fun upBookInfo() {
        if (book.name.isEmpty()) {
            book.name = book.originName.substringBeforeLast(".")
        }
        book.intro = "Word 文档（Legado 原生排版与多页浏览）"

        // 尝试提取首张图片作为封面
        try {
            val file = getDocxFile() ?: return
            if (!file.exists()) return
            if (book.coverUrl.isNullOrEmpty()) {
                book.coverUrl = LocalBook.getCoverPath(book)
            }
            if (File(book.coverUrl!!).exists()) return

            val zip = ZipFile(file)
            zip.use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val lower = entry.name.lowercase()
                    if (lower.startsWith("word/media/") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                        val input = z.getInputStream(entry)
                        val bitmap = BitmapFactory.decodeStream(input)
                        if (bitmap != null) {
                            val coverFile = FileUtils.createFileIfNotExist(book.coverUrl!!)
                            FileOutputStream(coverFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                out.flush()
                            }
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    @Synchronized
    private fun ensureParsed() {
        if (isParsed) return
        chapters.clear()
        chapterContents.clear()

        val file = getDocxFile()
        if (file == null || !file.exists()) {
            val defaultChapter = BookChapter().apply {
                index = 0
                bookUrl = book.bookUrl
                title = "正文"
                url = "docx_0"
            }
            chapters.add(defaultChapter)
            chapterContents[0] = "文件不存在或无法读取"
            isParsed = true
            return
        }

        try {
            val zip = ZipFile(file)
            zip.use { z ->
                // 1. 读取关系文件: rId -> target (如 rId4 -> media/image1.png)
                val relsMap = parseRelationships(z)

                // 2. 读取 word/document.xml
                val docEntry: ZipEntry? = z.getEntry("word/document.xml")
                if (docEntry == null) {
                    val defaultChapter = BookChapter().apply {
                        index = 0
                        bookUrl = book.bookUrl
                        title = "正文"
                        url = "docx_0"
                    }
                    chapters.add(defaultChapter)
                    chapterContents[0] = "无效的 Word 文档（缺少 word/document.xml）"
                    isParsed = true
                    return
                }

                val docStream = z.getInputStream(docEntry)
                val dbFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                val doc = dbFactory.newDocumentBuilder().parse(docStream)
                doc.documentElement.normalize()

                val bodyList = doc.getElementsByTagNameNS(NS_W, "body")
                if (bodyList.length == 0) {
                    val defaultChapter = BookChapter().apply {
                        index = 0
                        bookUrl = book.bookUrl
                        title = "正文"
                        url = "docx_0"
                    }
                    chapters.add(defaultChapter)
                    chapterContents[0] = "文档内容为空"
                    isParsed = true
                    return
                }

                val body = bodyList.item(0) as Element
                val children = body.childNodes

                var chapterIndex = 0
                var currentTitle = "第 1 页"
                val currentBuffer = StringBuilder()
                var currentTextLength = 0

                fun commitChapter(nextTitle: String) {
                    val htmlContent = currentBuffer.toString().trim()
                    if (htmlContent.isNotEmpty() || chapterIndex == 0) {
                        val formatted = HtmlFormatter.formatKeepImg(htmlContent.ifEmpty { "（空页）" })
                        chapterContents[chapterIndex] = formatted
                        val chapter = BookChapter().apply {
                            this.index = chapterIndex
                            this.bookUrl = book.bookUrl
                            this.title = currentTitle
                            this.url = "docx_$chapterIndex"
                        }
                        chapters.add(chapter)
                        chapterIndex++
                    }
                    currentBuffer.clear()
                    currentTextLength = 0
                    currentTitle = nextTitle
                }

                for (i in 0 until children.length) {
                    val child = children.item(i)
                    if (child.nodeType != Node.ELEMENT_NODE) continue
                    val element = child as Element
                    val localName = element.localName ?: element.nodeName.substringAfterLast(':')

                    when (localName) {
                        "p" -> {
                            val paraResult = parseParagraph(element, relsMap)
                            val text = paraResult.plainText

                            // 1. 显式分页符检测
                            if (paraResult.hasPageBreak && currentBuffer.isNotEmpty()) {
                                commitChapter("第 ${chapterIndex + 1} 页")
                            }

                            // 2. 标题大纲检测
                            if (paraResult.isHeading && text.isNotBlank() && currentBuffer.isNotEmpty()) {
                                commitChapter(text)
                            }

                            currentBuffer.append(paraResult.html)
                            currentTextLength += text.length

                            // 3. 长文档自动分页（按约 1600 字篇幅划分一页，保持单页阅读体验）
                            if (currentTextLength >= 1600) {
                                commitChapter("第 ${chapterIndex + 1} 页")
                            }
                        }

                        "tbl" -> {
                            val tableHtml = parseTable(element, relsMap)
                            currentBuffer.append(tableHtml)
                            currentTextLength += 200
                            if (currentTextLength >= 2000) {
                                commitChapter("第 ${chapterIndex + 1} 页")
                            }
                        }
                    }
                }

                // 提交最后一章
                if (currentBuffer.isNotEmpty() || chapters.isEmpty()) {
                    val formatted = HtmlFormatter.formatKeepImg(currentBuffer.toString().ifEmpty { "（正文结束）" })
                    chapterContents[chapterIndex] = formatted
                    val chapter = BookChapter().apply {
                        this.index = chapterIndex
                        this.bookUrl = book.bookUrl
                        this.title = currentTitle
                        this.url = "docx_$chapterIndex"
                    }
                    chapters.add(chapter)
                }
            }
            isParsed = true
        } catch (t: Throwable) {
            AppLog.put("解析 Docx 文档失败", t)
            t.printOnDebug()
            val defaultChapter = BookChapter().apply {
                index = 0
                bookUrl = book.bookUrl
                title = "正文"
                url = "docx_0"
            }
            chapters.add(defaultChapter)
            chapterContents[0] = "解析文档出错: ${t.localizedMessage}"
            isParsed = true
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        ensureParsed()
        return chapters
    }

    private fun getContent(chapter: BookChapter): String? {
        ensureParsed()
        return chapterContents[chapter.index] ?: chapterContents[0]
    }

    private fun getImage(href: String): InputStream? {
        val file = getDocxFile() ?: return null
        if (!file.exists()) return null
        val cleanHref = href.substringAfterLast('/')
        return try {
            val zip = ZipFile(file)
            val entry = zip.getEntry("word/media/$cleanHref")
                ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(cleanHref, ignoreCase = true) }
            if (entry != null) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                zip.close()
                ByteArrayInputStream(bytes)
            } else {
                zip.close()
                null
            }
        } catch (e: Exception) {
            AppLog.put("获取 Docx 图片失败: $href", e)
            null
        }
    }

    private fun parseRelationships(zip: ZipFile): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val relsEntry = zip.getEntry("word/_rels/document.xml.rels") ?: return map
        try {
            val stream = zip.getInputStream(relsEntry)
            val dbFactory = DocumentBuilderFactory.newInstance()
            val doc = dbFactory.newDocumentBuilder().parse(stream)
            val list = doc.getElementsByTagName("Relationship")
            for (i in 0 until list.length) {
                val el = list.item(i) as Element
                val id = el.getAttribute("Id")
                val target = el.getAttribute("Target")
                if (id.isNotEmpty() && target.isNotEmpty()) {
                    map[id] = target
                }
            }
        } catch (e: Exception) {
            AppLog.put("解析 word/_rels/document.xml.rels 失败", e)
        }
        return map
    }

    private data class ParaParseResult(
        val html: String,
        val plainText: String,
        val hasPageBreak: Boolean,
        val isHeading: Boolean
    )

    private fun parseParagraph(p: Element, relsMap: Map<String, String>): ParaParseResult {
        var hasPageBreak = false
        var isHeading = false
        var headingLevel = 1
        var align = ""

        // 解析段落属性
        val pPr = getChildByLocalName(p, "pPr")
        if (pPr != null) {
            val pStyle = getChildByLocalName(pPr, "pStyle")
            if (pStyle != null) {
                val styleVal = pStyle.getAttributeNS(NS_W, "val").ifEmpty { pStyle.getAttribute("val") }.lowercase()
                if (styleVal.contains("heading") || styleVal.contains("标题") || styleVal.contains("title")) {
                    isHeading = true
                    headingLevel = styleVal.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 6) ?: 1
                }
            }
            val jc = getChildByLocalName(pPr, "jc")
            if (jc != null) {
                val v = jc.getAttributeNS(NS_W, "val").ifEmpty { jc.getAttribute("val") }
                align = when (v) {
                    "center" -> "center"
                    "right" -> "right"
                    "both", "distribute" -> "justify"
                    else -> ""
                }
            }
            // 节分页
            val sectPr = getChildByLocalName(pPr, "sectPr")
            if (sectPr != null) {
                hasPageBreak = true
            }
        }

        val textBuilder = StringBuilder()
        val htmlBuilder = StringBuilder()

        val children = p.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val el = child as Element
            val localName = el.localName ?: el.nodeName.substringAfterLast(':')

            when (localName) {
                "r" -> {
                    // 检查是否包含分页符
                    val brs = el.getElementsByTagNameNS(NS_W, "br")
                    for (b in 0 until brs.length) {
                        val br = brs.item(b) as Element
                        val type = br.getAttributeNS(NS_W, "type").ifEmpty { br.getAttribute("type") }
                        if (type == "page") {
                            hasPageBreak = true
                        }
                    }
                    if (el.getElementsByTagNameNS(NS_W, "lastRenderedPageBreak").length > 0) {
                        hasPageBreak = true
                    }

                    // 检查图片引用
                    val drawings = el.getElementsByTagNameNS(NS_W, "drawing")
                    for (d in 0 until drawings.length) {
                        val draw = drawings.item(d) as Element
                        val blips = draw.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main", "blip")
                        for (bp in 0 until blips.length) {
                            val blip = blips.item(bp) as Element
                            val embed = blip.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
                                .ifEmpty { blip.getAttribute("r:embed") }
                            val target = relsMap[embed]
                            if (!target.isNullOrEmpty()) {
                                val fileName = target.substringAfterLast('/')
                                htmlBuilder.append("<p align=\"center\"><img src=\"$fileName\"></p>\n")
                            }
                        }
                    }

                    // 文本
                    val rPr = getChildByLocalName(el, "rPr")
                    val isBold = rPr != null && getChildByLocalName(rPr, "b") != null
                    val isItalic = rPr != null && getChildByLocalName(rPr, "i") != null
                    val isUnderline = rPr != null && getChildByLocalName(rPr, "u") != null
                    val isStrike = rPr != null && getChildByLocalName(rPr, "strike") != null

                    val tNodes = el.getElementsByTagNameNS(NS_W, "t")
                    for (t in 0 until tNodes.length) {
                        val tEl = tNodes.item(t)
                        val content = tEl.textContent ?: ""
                        if (content.isNotEmpty()) {
                            textBuilder.append(content)
                            var escaped = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                            if (isBold) escaped = "<b>$escaped</b>"
                            if (isItalic) escaped = "<i>$escaped</i>"
                            if (isUnderline) escaped = "<u>$escaped</u>"
                            if (isStrike) escaped = "<s>$escaped</s>"
                            htmlBuilder.append(escaped)
                        }
                    }
                }
            }
        }

        val plainText = textBuilder.toString().trim()
        val runsHtml = htmlBuilder.toString()
        val finalHtml = if (runsHtml.isNotEmpty()) {
            val alignAttr = if (align.isNotEmpty()) " align=\"$align\"" else ""
            if (isHeading) {
                "<h$headingLevel$alignAttr>$runsHtml</h$headingLevel>\n"
            } else {
                "<p$alignAttr>$runsHtml</p>\n"
            }
        } else ""

        return ParaParseResult(finalHtml, plainText, hasPageBreak, isHeading)
    }

    private fun parseTable(tbl: Element, relsMap: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\" style=\"width:100%; border-collapse:collapse;\">\n")
        val children = tbl.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val rowEl = child as Element
            val rowName = rowEl.localName ?: rowEl.nodeName.substringAfterLast(':')
            if (rowName == "tr") {
                sb.append("  <tr>\n")
                val trChildren = rowEl.childNodes
                for (j in 0 until trChildren.length) {
                    val tc = trChildren.item(j)
                    if (tc.nodeType != Node.ELEMENT_NODE) continue
                    val tcEl = tc as Element
                    val cellName = tcEl.localName ?: tcEl.nodeName.substringAfterLast(':')
                    if (cellName == "tc") {
                        sb.append("    <td>")
                        val tcChildren = tcEl.childNodes
                        for (k in 0 until tcChildren.length) {
                            val p = tcChildren.item(k)
                            if (p.nodeType == Node.ELEMENT_NODE && (p.localName == "p" || p.nodeName.endsWith(":p"))) {
                                val res = parseParagraph(p as Element, relsMap)
                                sb.append(res.html)
                            }
                        }
                        sb.append("</td>\n")
                    }
                }
                sb.append("  </tr>\n")
            }
        }
        sb.append("</table>\n")
        return sb.toString()
    }

    private fun getChildByLocalName(parent: Element, name: String): Element? {
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val node = list.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                val ln = el.localName ?: el.nodeName.substringAfterLast(':')
                if (ln == name) return el
            }
        }
        return null
    }

    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
}
