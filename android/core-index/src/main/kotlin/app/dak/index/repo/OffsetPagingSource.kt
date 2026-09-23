package app.dak.index.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An offset-keyed [PagingSource] over arbitrary suspend queries that invalidates itself when any of [tables]
 * changes (the same mechanism Room's generated paging sources use). Subclasses implement [loadRange].
 *
 * Keys are item offsets. For a prepend, the key is the *exclusive end* of the range to load (the offset of the
 * first loaded page), mirroring Room's `LimitOffsetPagingSource`, so pages never overlap.
 */
internal abstract class OffsetPagingSource<V : Any>(
    private val db: RoomDatabase,
    tables: Array<String>,
) : PagingSource<Int, V>() {

    private val registered = AtomicBoolean(false)

    private val observer = object : InvalidationTracker.Observer(tables) {
        override fun onInvalidated(tables: Set<String>) {
            invalidate()
        }
    }

    init {
        registerInvalidatedCallback { db.invalidationTracker.removeObserver(observer) }
    }

    /** Loads up to [limit] items starting at [offset] (fewer only at the end of the data). */
    protected abstract suspend fun loadRange(offset: Int, limit: Int): List<V>

    final override suspend fun load(params: LoadParams<Int>): LoadResult<Int, V> = withContext(Dispatchers.IO) {
        if (registered.compareAndSet(false, true)) db.invalidationTracker.addObserver(observer)
        try {
            val key = params.key ?: 0
            val (offset, limit) = when (params) {
                is LoadParams.Prepend -> {
                    val start = maxOf(0, key - params.loadSize)
                    start to (key - start)
                }
                else -> maxOf(0, key) to params.loadSize
            }
            val items = if (limit <= 0) emptyList() else loadRange(offset, limit)
            val result: LoadResult<Int, V> = if (invalid) {
                LoadResult.Invalid<Int, V>()
            } else {
                LoadResult.Page<Int, V>(
                    data = items,
                    prevKey = if (offset <= 0) null else offset,
                    nextKey = if (items.size < limit) null else offset + items.size,
                )
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error<Int, V>(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, V>): Int? =
        state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }
}
