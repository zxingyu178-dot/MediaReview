package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.model.FolderTreeNodeDto
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.model.*
import org.junit.Assert.*
import org.junit.Test

class FolderTreeContractTest {
    private val mapper = V2MediaMapper(MediaUrlResolver())
    @Test fun parentChildMappingAndCoverURLRemainIndependentOfMediaPage() {
        val mapped = mapper.mapFolderNode(FolderTreeNodeDto(
            folder_id = "ft_action", parent_id = "ft_movies", library_id = "lib1", name = "动作",
            child_count = 2, direct_media_count = 3, recursive_media_count = 50,
            direct_image_count = 1, cover_url = "/cover/remote", direct_cover_url = "/cover/direct",
        ), "https://mediareview.test")
        assertEquals("ft_movies", mapped.parentId)
        assertEquals(50, mapped.recursiveMediaCount)
        assertEquals("https://mediareview.test/cover/remote", mapped.coverUrl)
        assertEquals("https://mediareview.test/cover/direct", mapped.directCoverUrl)
    }
    @Test fun oneLibraryUsesFirstLevelButMultipleLibrariesUseRoots() {
        val a = V2FolderNode("r1", null, "lib1", "媒体", 1, 0, 10, 0)
        val child = V2FolderNode("movies", "r1", "lib1", "电影", 1, 0, 10, 0)
        val leaf = V2FolderNode("action", "movies", "lib1", "动作", 0, 10, 10, 0)
        assertEquals(listOf(child), homeFolderRoots(listOf(a, child, leaf)))
        val b = V2FolderNode("r2", null, "lib2", "照片", 0, 4, 4, 4)
        assertEquals(listOf(a, b), homeFolderRoots(listOf(a, child, leaf, b)))
        assertEquals(listOf(a, child, leaf), folderBreadcrumbs(listOf(a, child, leaf), "action"))
    }
    @Test fun missingTreeCapabilityDoesNotDisableContract2Business() {
        val status = V2ServerStatusStore()
        status.recordCompatible("1.2.2", 2)
        assertFalse(status.supportsFolderTree)
        assertEquals(V2ServerStatus.Online, status.status.value)
        status.recordCompatible("1.3.0", 2, listOf("folder_tree_v1"))
        assertTrue(status.supportsFolderTree)
        status.reset()
        assertFalse(status.supportsFolderTree)
    }
}
