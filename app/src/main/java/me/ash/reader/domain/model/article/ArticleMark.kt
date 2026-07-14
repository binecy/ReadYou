package me.ash.reader.domain.model.article

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "article_mark"
)
data class ArticleMark (
    @PrimaryKey
    var id: String,

    @ColumnInfo(index = true)
    var articleId: String,

    @ColumnInfo
    var date: Date,

    @ColumnInfo
    var markText: String,

    @ColumnInfo
    var markTextJson: String,

    @ColumnInfo
    var idea: String? = null,
) {

}