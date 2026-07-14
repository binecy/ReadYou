package me.ash.reader.domain.model.article

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import me.ash.reader.domain.model.feed.Feed

/**
 * An [article] contains a [feed].
 */
data class ArticleWithFeed(
    @Embedded
    var article: Article,
    @Relation(parentColumn = "feedId", entityColumn = "id")
    var feed: Feed,

    @ColumnInfo(name = "markCount")
    var markCount: Int? = 0  // 文章划线数量
)
