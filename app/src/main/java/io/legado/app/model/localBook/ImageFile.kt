package io.legado.app.model.localBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import java.io.InputStream

/**
 * 本地图片书籍解析器。
 * 支持单图/漫画书籍信息解析、章节建立与点读内容承载。
 */
class ImageFile(var book: Book) {

    companion object : BaseLocalBookParse {

        override fun upBookInfo(book: Book) {
            if (book.name.isEmpty()) {
                book.name = book.originName.substringBeforeLast(".")
            }
            book.intro = "本地图片书籍（支持离线 OCR 点读与逐句朗读）"
            // 将自身图片 URI 设置为封面，书架中可直接显示图片预览
            book.coverUrl = book.bookUrl
        }

        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            val list = ArrayList<BookChapter>()
            val chapter = BookChapter().apply {
                this.index = 0
                this.bookUrl = book.bookUrl
                this.title = book.name.ifEmpty { "图片正文" }
                this.url = "image_main"
            }
            list.add(chapter)
            return list
        }

        override fun getContent(book: Book, chapter: BookChapter): String? {
            return "<img src=\"${book.bookUrl}\">"
        }

        override fun getImage(book: Book, href: String): InputStream? {
            return null
        }
    }
}
