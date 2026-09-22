package io.legado.app.model.localBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.isContentScheme
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class DocxFile(var book: Book) {

    companion object : BaseLocalBookParse {

        override fun upBookInfo(book: Book) {
            if (book.name.isEmpty()) {
                book.name = book.originName.substringBeforeLast(".")
            }
            book.intro = "Word 文档（支持原文排版与点读模式）"
        }

        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            val list = ArrayList<BookChapter>()
            val chapter = BookChapter().apply {
                this.index = 0
                this.bookUrl = book.bookUrl
                this.title = book.name.ifEmpty { "正文" }
                this.url = "docx_main"
            }
            list.add(chapter)
            return list
        }

        override fun getContent(book: Book, chapter: BookChapter): String? {
            return "Word 原文文档请在原文视图中阅读与点读"
        }

        override fun getImage(book: Book, href: String): InputStream? {
            return null
        }
    }
}
