package com.srisomesh.reelcut.engine

/**
 * Timeline editor model (phase 2a). Pure Kotlin so the edits can be unit-tested.
 *
 * A clip plays [inS]..[outS] seconds of its source. Photos have no source length: [inS] is 0 and
 * [outS] is how long the photo shows.
 */
data class TClip(
    val id: Long,
    val uri: String,
    val isImage: Boolean,
    val srcDur: Double,     // whole video length (0 for photos)
    val inS: Double,
    val outS: Double,
) {
    val dur: Double get() = outS - inS
}

data class Project(
    val id: String,
    val name: String,
    val clips: List<TClip>,
    val created: Long,
    val updated: Long,
) {
    val total: Double get() = clips.sumOf { it.dur }

    /** Timeline start time of every clip. */
    fun starts(): DoubleArray {
        val s = DoubleArray(clips.size)
        var t = 0.0
        for (i in clips.indices) { s[i] = t; t += clips[i].dur }
        return s
    }

    fun indexOf(id: Long) = clips.indexOfFirst { it.id == id }

    /** The clip playing at timeline time [t] and the time inside it. */
    fun clipAt(t: Double): Pair<Int, Double>? {
        if (clips.isEmpty()) return null
        val s = starts()
        for (i in clips.indices) if (t < s[i] + clips[i].dur) return i to (t - s[i]).coerceAtLeast(0.0)
        return clips.lastIndex to clips.last().dur
    }
}

/** Every edit returns a new project, or null when it is not possible. */
object TimelineEdits {
    const val MIN_CLIP = 0.2          // shortest clip (s)
    const val PHOTO_DEFAULT = 3.0     // a new photo shows this long
    const val PHOTO_MAX = 30.0

    private fun nextId(p: Project) = (p.clips.maxOfOrNull { it.id } ?: 0L) + 1

    private fun Project.with(clips: List<TClip>) = copy(clips = clips)

    /** Drag of the left edge by [delta] s (positive = clip starts later, gets shorter). Photos change length. */
    fun trimLeft(p: Project, id: Long, delta: Double): Project? {
        val i = p.indexOf(id); if (i < 0) return null
        val c = p.clips[i]
        val n = if (c.isImage) c.copy(outS = (c.outS - delta).coerceIn(MIN_CLIP, PHOTO_MAX))
        else c.copy(inS = (c.inS + delta).coerceIn(0.0, c.outS - MIN_CLIP))
        if (n == c) return null
        return p.with(p.clips.toMutableList().also { it[i] = n })
    }

    /** Drag of the right edge by [delta] s (positive = clip ends later, gets longer). */
    fun trimRight(p: Project, id: Long, delta: Double): Project? {
        val i = p.indexOf(id); if (i < 0) return null
        val c = p.clips[i]
        val n = if (c.isImage) c.copy(outS = (c.outS + delta).coerceIn(MIN_CLIP, PHOTO_MAX))
        else c.copy(outS = (c.outS + delta).coerceIn(c.inS + MIN_CLIP, c.srcDur))
        if (n == c) return null
        return p.with(p.clips.toMutableList().also { it[i] = n })
    }

    /** Cuts the clip under timeline time [t] in two. Null when [t] is too close to a clip edge. */
    fun split(p: Project, t: Double): Pair<Project, Long>? {
        val (i, local) = p.clipAt(t) ?: return null
        val c = p.clips[i]
        if (local < MIN_CLIP || c.dur - local < MIN_CLIP) return null
        val id2 = nextId(p)
        val a: TClip
        val b: TClip
        if (c.isImage) {
            a = c.copy(outS = local)
            b = c.copy(id = id2, outS = c.dur - local)
        } else {
            a = c.copy(outS = c.inS + local)
            b = c.copy(id = id2, inS = c.inS + local)
        }
        val list = p.clips.toMutableList()
        list[i] = a
        list.add(i + 1, b)
        return p.with(list) to id2
    }

    fun duplicate(p: Project, id: Long): Pair<Project, Long>? {
        val i = p.indexOf(id); if (i < 0) return null
        val id2 = nextId(p)
        val list = p.clips.toMutableList()
        list.add(i + 1, p.clips[i].copy(id = id2))
        return p.with(list) to id2
    }

    fun delete(p: Project, id: Long): Project? {
        val i = p.indexOf(id); if (i < 0) return null
        return p.with(p.clips.toMutableList().also { it.removeAt(i) })
    }

    /** Moves clip [id] so it ends up at position [to] (0 = first). */
    fun move(p: Project, id: Long, to: Int): Project? {
        val i = p.indexOf(id); if (i < 0) return null
        val target = to.coerceIn(0, p.clips.lastIndex)
        if (target == i) return null
        val list = p.clips.toMutableList()
        val c = list.removeAt(i)
        list.add(target, c)
        return p.with(list)
    }

    /** Adds clips (their ids are replaced) at position [at]; returns the project and the first new id. */
    fun add(p: Project, at: Int, clips: List<TClip>): Pair<Project, Long>? {
        if (clips.isEmpty()) return null
        var id = nextId(p)
        val first = id
        val fresh = clips.map { it.copy(id = id++) }
        val list = p.clips.toMutableList()
        list.addAll(at.coerceIn(0, list.size), fresh)
        return p.with(list) to first
    }

    /** Where clip [id] would land if dropped with its centre at timeline time [t]. */
    fun dropIndex(p: Project, id: Long, t: Double): Int {
        val others = p.clips.filter { it.id != id }
        var acc = 0.0
        for ((k, c) in others.withIndex()) {
            if (t < acc + c.dur / 2) return k
            acc += c.dur
        }
        return others.size
    }
}
