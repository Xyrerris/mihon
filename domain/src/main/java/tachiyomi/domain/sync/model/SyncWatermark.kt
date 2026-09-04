package tachiyomi.domain.sync.model

/**
 * Where the last successful sync left off.
 *
 * Two numbers, because they are read from two clocks and confusing them loses writes.
 *
 * [since] belongs to the server. It is the `now` the server returned last time, and the only thing
 * it is ever compared against is the server's own record of when a change arrived. The client never
 * produces it and never interprets it.
 *
 * [pushedThrough] belongs to this device. It is the highest `last_modified_at` this device has
 * already sent, and it selects what to send next. Using [since] for that -- which is the obvious
 * shortcut, one number instead of two -- would compare a server timestamp against local rows, so a
 * device whose clock runs behind the server's would skip every change it made in the interval and
 * never notice.
 *
 * Both are zero for a device that has never synced, or has been pointed at a different server,
 * which asks for and sends everything.
 */
data class SyncWatermark(
    val since: Long,
    val pushedThrough: Long,
) {
    companion object {
        val NONE = SyncWatermark(since = 0L, pushedThrough = 0L)
    }
}
