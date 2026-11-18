package com.boneink.picapica.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val WideLayoutMinWidth = 600.dp
internal val ExpandedLayoutMinWidth = 840.dp
internal val LocalWideLayout = compositionLocalOf { false }

@Composable
internal fun adaptiveColumns(minCellWidth: Dp = 152.dp): GridCells =
    if (LocalWideLayout.current) GridCells.Adaptive(minCellWidth) else GridCells.Fixed(2)

internal fun LazyGridScope.fullWidthItem(content: @Composable ColumnScope.() -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { Column(content = content) }
}
