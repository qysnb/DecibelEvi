package com.decibelevidence.app.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class ArchiveIntegrityTest {
    @Test fun archiveContainsManifestAndDetectsChangedFile() {
        val root = Files.createTempDirectory("evidence-test").toFile()
        try {
            val source = File(root, "record").apply { mkdirs() }
            File(source, "measurements.csv").writeText("时间,分贝\n0,60\n")
            File(source, "照片.jpg").writeBytes(byteArrayOf(1, 2, 3))
            val zip = File(root, "out.zip")
            EvidenceArchive.create(source, zip)
            ZipFile(zip).use { archive ->
                assertNotNull(archive.getEntry("SHA256SUMS.txt"))
                assertNotNull(archive.getEntry("照片.jpg"))
                assertEquals(3, archive.size())
            }
            assertTrue(EvidenceArchive.verify(source).isEmpty())
            File(source, "照片.jpg").appendBytes(byteArrayOf(4))
            assertEquals(listOf("照片.jpg"), EvidenceArchive.verify(source))
        } finally { root.deleteRecursively() }
    }
    @Test fun unfinishedVideoIsExcluded() {
        val root = Files.createTempDirectory("evidence-partial").toFile()
        try {
            File(root, "bad.mp4.part").writeText("incomplete")
            File(root, "valid.txt").writeText("complete")
            val target = File(root.parentFile, root.name + ".zip")
            try {
                EvidenceArchive.create(root, target)
                ZipFile(target).use { assertNull(it.getEntry("bad.mp4.part")) }
            } finally { target.delete() }
        } finally { root.deleteRecursively() }
    }
}
