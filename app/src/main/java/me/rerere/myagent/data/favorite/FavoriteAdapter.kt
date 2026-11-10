package me.rerere.myagent.data.favorite

import me.rerere.myagent.data.db.entity.FavoriteEntity
import me.rerere.myagent.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
