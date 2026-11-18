package app.megaglass.android.core.mega

/**
 * [handles] without the items that have an ancestor in [handles]. A folder carries its content,
 * so an item inside a selected folder must not be copied, downloaded, deleted or restored a
 * second time. [parent] gives the parent handle, or null at the top.
 */
internal fun withoutNested(handles: List<Long>, parent: (Long) -> Long?): List<Long> {
    val selected = handles.toSet()
    return handles.filter { handle -> generateSequence(parent(handle), parent).none { it in selected } }
}
