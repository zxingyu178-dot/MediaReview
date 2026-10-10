package com.mediareview.app.feature.v2.model

data class V2FolderNode(
    val id: String,
    val parentId: String?,
    val libraryId: String,
    val name: String,
    val childCount: Int,
    val directMediaCount: Int,
    val recursiveMediaCount: Int,
    val directImageCount: Int,
    val coverUrl: String? = null,
    val directCoverUrl: String? = null,
)

/** 单库直接展示一级目录；多库先选择库。仅一个无子目录的库仍可浏览直接媒体。 */
fun homeFolderRoots(tree: List<V2FolderNode>): List<V2FolderNode> {
    val roots = tree.filter { it.parentId == null }
    if (roots.size != 1) return roots
    return tree.filter { it.parentId == roots.single().id }.ifEmpty { roots }
}

fun folderBreadcrumbs(tree: List<V2FolderNode>, folderId: String?): List<V2FolderNode> {
    val byId = tree.associateBy { it.id }
    val visited = mutableSetOf<String>()
    val path = mutableListOf<V2FolderNode>()
    var current = folderId
    while (current != null && visited.add(current)) {
        val node = byId[current] ?: break
        path += node
        current = node.parentId
    }
    return path.reversed()
}
