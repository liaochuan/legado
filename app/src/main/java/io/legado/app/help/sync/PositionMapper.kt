package io.legado.app.help.sync

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocalTxt

interface PositionMapper {
    fun toRemote(progress: BookProgress, anchorText: String?): RemotePosition?
    fun fromRemote(xpointer: String, percentage: Double): BookProgress?
    fun fromPercentage(percentage: Double): BookProgress?
    fun paragraphKey(progress: BookProgress): String?
}

object PositionMapperFactory {
    fun create(book: Book): PositionMapper? {
        return when {
            book.isLocalTxt -> TxtPositionMapper(
                book = book,
                source = BookTxtSource(book),
                chapters = { appDb.bookChapterDao.getChapterList(book.bookUrl) },
            )

            book.isEpub -> EpubPositionMapper(
                book = book,
                spine = EpubFileSpineSource(book),
                chapters = { appDb.bookChapterDao.getChapterList(book.bookUrl) },
            )

            else -> null
        }
    }
}
