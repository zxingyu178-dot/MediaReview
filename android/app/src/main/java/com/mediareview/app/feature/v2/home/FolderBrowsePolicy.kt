package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.*

/** 单库根直接展开，但不丢弃根内散落文件；多库根只列库。 */
internal fun implicitFolderRoot(tree: List<V2FolderNode>): String? =
    tree.filter { it.parentId == null }.singleOrNull()?.id

internal fun folderChildren(tree: List<V2FolderNode>, folderId: String?, spec: V2SortSpec): List<V2FolderNode> {
    val parent = folderId ?: implicitFolderRoot(tree)
    val children = tree.filter { it.parentId == parent }.sortedWith(compareBy<V2FolderNode> { it.name.lowercase() }.thenBy { it.id })
    return if (spec.field == V2SortField.NAME && spec.order == V2SortOrder.DESC) children.reversed() else children
}
