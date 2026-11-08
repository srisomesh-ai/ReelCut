package com.srisomesh.reelcut.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEditsTest {

    private fun video(id: Long, src: Double, inS: Double = 0.0, outS: Double = src) = TClip(id, "v$id", false, src, inS, outS)
    private fun photo(id: Long, d: Double = 3.0) = TClip(id, "p$id", true, 0.0, 0.0, d)
    private val p = Project("x", "Test", listOf(video(1, 10.0, 2.0, 6.0), photo(2), video(3, 5.0)), 0, 0)

    @Test
    fun startsAndClipAt() {
        assertEquals(12.0, p.total, 1e-9)
        assertEquals(listOf(0.0, 4.0, 7.0), p.starts().toList())
        assertEquals(1 to 1.0, p.clipAt(5.0))
        assertEquals(2 to 5.0, p.clipAt(99.0))
    }

    @Test
    fun trimVideoKeepsInsideSource() {
        val a = TimelineEdits.trimLeft(p, 1, -5.0)!!          // start can't go before 0
        assertEquals(0.0, a.clips[0].inS, 1e-9)
        val b = TimelineEdits.trimRight(p, 1, 100.0)!!        // end can't pass the source end
        assertEquals(10.0, b.clips[0].outS, 1e-9)
        val c = TimelineEdits.trimLeft(p, 1, 100.0)!!         // never shorter than MIN_CLIP
        assertEquals(TimelineEdits.MIN_CLIP, c.clips[0].dur, 1e-9)
        assertNull(TimelineEdits.trimRight(b, 1, 1.0))        // nothing changes -> null
    }

    @Test
    fun trimPhotoChangesLength() {
        val a = TimelineEdits.trimRight(p, 2, 2.0)!!
        assertEquals(5.0, a.clips[1].dur, 1e-9)
        val b = TimelineEdits.trimLeft(p, 2, 1.0)!!
        assertEquals(2.0, b.clips[1].dur, 1e-9)
        assertEquals(0.0, b.clips[1].inS, 1e-9)
    }

    @Test
    fun splitKeepsTotalAndSource() {
        val (q, newId) = TimelineEdits.split(p, 3.0)!!       // 3 s into clip 1 (source 2..6)
        assertEquals(4, q.clips.size)
        assertEquals(p.total, q.total, 1e-9)
        assertEquals(5.0, q.clips[0].outS, 1e-9)
        assertEquals(5.0, q.clips[1].inS, 1e-9)
        assertEquals(newId, q.clips[1].id)
        assertNull(TimelineEdits.split(p, 4.05))              // too close to a cut
        val (r, _) = TimelineEdits.split(p, 5.0)!!            // photo
        assertEquals(p.total, r.total, 1e-9)
        assertTrue(r.clips[1].isImage && r.clips[2].isImage)
    }

    @Test
    fun moveDuplicateDeleteAdd() {
        val m = TimelineEdits.move(p, 3, 0)!!
        assertEquals(listOf(3L, 1L, 2L), m.clips.map { it.id })
        assertNull(TimelineEdits.move(p, 3, 2))
        val (d, id) = TimelineEdits.duplicate(p, 2)!!
        assertEquals(listOf(1L, 2L, id, 3L), d.clips.map { it.id })
        assertEquals(4L, id)
        val x = TimelineEdits.delete(p, 1)!!
        assertEquals(listOf(2L, 3L), x.clips.map { it.id })
        val (a, first) = TimelineEdits.add(p, 1, listOf(photo(1), photo(1)))!!
        assertEquals(listOf(1L, 4L, 5L, 2L, 3L), a.clips.map { it.id })
        assertEquals(4L, first)
        // ids stay unique after any mix of edits
        val all = listOf(m, d, x, a).flatMap { q -> listOf(q.clips.map { it.id }.toSet().size == q.clips.size) }
        assertTrue(all.all { it })
    }

    @Test
    fun dropIndex() {
        // dropping clip 3 (5 s) with its centre at 1 s -> first place; at 6 s -> between 1 and the photo
        assertEquals(0, TimelineEdits.dropIndex(p, 3, 1.0))
        assertEquals(1, TimelineEdits.dropIndex(p, 3, 4.5))
        assertEquals(2, TimelineEdits.dropIndex(p, 3, 6.0))
        assertNotNull(TimelineEdits.move(p, 3, TimelineEdits.dropIndex(p, 3, 1.0)))
    }
}
