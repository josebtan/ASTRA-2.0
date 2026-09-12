package com.astra.camera

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Aplica un espaciado uniforme entre todas las celdas de una cuadrícula,
 * incluyendo los bordes, para que el mosaico de fotos se vea parejo y ordenado.
 */
class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacingPx: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val column = position % spanCount

        outRect.left = spacingPx - column * spacingPx / spanCount
        outRect.right = (column + 1) * spacingPx / spanCount

        if (position < spanCount) {
            outRect.top = spacingPx
        }
        outRect.bottom = spacingPx
    }
}
