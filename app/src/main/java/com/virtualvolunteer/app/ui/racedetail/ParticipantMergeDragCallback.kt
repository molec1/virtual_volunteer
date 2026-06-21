package com.virtualvolunteer.app.ui.racedetail

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.virtualvolunteer.app.data.local.ParticipantDashboardRow

/**
 * Enables long-press drag on participant rows so the operator can drop one row onto another to
 * merge them (donor → keeper). The list does NOT reorder during drag — only the drag source is
 * dimmed and the current drop target gets a coloured stroke highlight.
 *
 * When the user releases over a different row, [onMergeRequest] is called with the dragged row
 * (donor) and the row it was dropped onto (keeper).
 */
internal class ParticipantMergeDragCallback(
    private val adapter: ParticipantDashboardAdapter,
    private val onMergeRequest: (donor: ParticipantDashboardRow, keeper: ParticipantDashboardRow) -> Unit,
) : ItemTouchHelper.Callback() {

    override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
        makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

    override fun isLongPressDragEnabled(): Boolean = true
    override fun isItemViewSwipeEnabled(): Boolean = false
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(vh, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                adapter.setDragPositions(sourcePos = pos, targetPos = -1)
            }
        }
    }

    override fun onMove(
        rv: RecyclerView,
        dragged: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        val sourcePos = adapter.dragSourcePosition
        val targetPos = target.bindingAdapterPosition
        if (sourcePos >= 0 && targetPos >= 0 && targetPos != sourcePos) {
            adapter.setDragPositions(sourcePos, targetPos)
        }
        // Return false: we never reorder the list — drag is purely for the merge gesture.
        return false
    }

    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
        super.clearView(rv, vh)
        val sourcePos = adapter.dragSourcePosition
        val targetPos = adapter.dropTargetPosition
        val donor = if (sourcePos in 0 until adapter.itemCount) adapter.getItemAt(sourcePos) else null
        val keeper = if (targetPos in 0 until adapter.itemCount && targetPos != sourcePos) adapter.getItemAt(targetPos) else null
        adapter.setDragPositions(-1, -1)
        if (donor != null && keeper != null) {
            onMergeRequest(donor, keeper)
        }
    }
}
