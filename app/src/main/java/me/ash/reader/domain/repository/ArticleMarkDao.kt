package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleMark
import java.util.Date

@Dao
interface ArticleMarkDao {
    @Insert
    fun insert(vararg articleMark: ArticleMark)

    @Query(
        """
        select * FROM article_mark
        WHERE articleId = :articleId
        """
    )
    suspend fun queryArticleMark(
        articleId: String
    ): List<ArticleMark>

    @Query(
        """
        DELETE FROM article_mark
        WHERE id = :markId
        """
    )
    suspend fun deleteById(markId: String)
}