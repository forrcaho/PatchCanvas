package io.github.forrcaho.patchcanvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Undo, as a stack of whole patches.
 *
 * Snapshots rather than inverted commands. A patch serialises to a few kilobytes of the
 * JSON we already produce, so fifty of them cost nothing -- and there are no inverses to
 * get wrong. The command approach needs every mutation to have a correct opposite, and
 * the one you forget is a silent corruption rather than a crash.
 *
 * Settled states only. Fed from the same debounce as autosave, a continuous knob drag
 * arrives as one entry instead of three hundred.
 */
class History(private val limit: Int = 50) {

    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    private var current: String? = null

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /**
     * A settled state arrived.
     *
     * Restoring one makes it the current state first, so when the change propagates back
     * through here it compares equal and records nothing. That is what stops an undo from
     * being pushed onto its own stack, without needing a flag and the race that comes
     * with one.
     */
    fun record(json: String) {
        if (json == current) return
        current?.let {
            past.addLast(it)
            if (past.size > limit) past.removeFirst()
        }
        current = json
        future.clear()
        refresh()
    }

    fun undo(): String? {
        val previous = past.removeLastOrNull() ?: return null
        current?.let { future.addLast(it) }
        current = previous
        refresh()
        return previous
    }

    fun redo(): String? {
        val next = future.removeLastOrNull() ?: return null
        current?.let { past.addLast(it) }
        current = next
        refresh()
        return next
    }

    private fun refresh() {
        canUndo = past.isNotEmpty()
        canRedo = future.isNotEmpty()
    }
}

/**
 * Makes this patch look like [source], in place.
 *
 * In place because the patch is referenced by the composition, the autosave and the graph
 * sync; swapping the object would leave all three pointing at the old one. The rails are
 * kept and only their knobs copied, since a snapshot does not describe their geometry.
 *
 * All of it inside one mutable snapshot, which is not a tidiness point. Applied
 * piecemeal, the observer behind `GraphSync` can see the moment after the cables are
 * cleared and before they are restored -- and an empty patch is a real state to the
 * engine, which would dutifully crossfade every voice to silence and back. Undo would
 * click. One atomic apply means the diff only ever sees before and after.
 *
 * Which panel is open is carried across by id. Undo changes the document, not the view:
 * the camera does not move, and neither should the thing you are looking at. Free
 * modules are rebuilt as new objects here, so without this an undo from inside a panel
 * would slam it shut -- which is the one moment you most want to watch, since the knob
 * you just moved is on screen and about to move back.
 */
fun Patch.replaceWith(source: Patch): Set<Long> {
    val changed = changesFrom(source)

    Snapshot.withMutableSnapshot {
        val openId = modules.firstOrNull { it.expanded }?.id

        connections.clear()
        modules.removeAll { !it.isPinned }

        source.free.forEach { from ->
            val copy = PatchModule(from.id, from.type, from.position)
            from.params.forEachIndexed { index, value -> copy.setParam(index, value) }
            from.steps.forEachIndexed { index, step -> copy.setStep(index, step) }
            adopt(copy)
        }

        source.pinned.forEach { from ->
            module(from.id)?.let { rail ->
                from.params.forEachIndexed { index, value -> rail.setParam(index, value) }
            }
        }

        source.connections.forEach { connections.add(it) }

        // Null if the snapshot predates the module, in which case there is nothing left
        // to have open and the panel closing is the right answer.
        openId?.let { id -> module(id)?.expanded = true }
    }

    return changed
}

/**
 * Which modules [source] would disturb, computed before anything moves.
 *
 * The caller decides what to do with it; `replaceWith` deliberately does not pulse
 * anything itself, so loading a patch from a file can stay silent while an undo does not.
 * Ids of modules that vanish are included and simply never drawn, which is cheaper than
 * filtering them and reads the same.
 */
private fun Patch.changesFrom(source: Patch): Set<Long> {
    val before = modules.associateBy { it.id }
    val after = source.modules.associateBy { it.id }
    val changed = mutableSetOf<Long>()

    changed += before.keys - after.keys
    changed += after.keys - before.keys
    after.forEach { (id, now) ->
        val was = before[id] ?: return@forEach
        // toList() on both sides deliberately: SnapshotStateList does not implement
        // structural equality, so comparing the lists directly is an identity check
        // that is always false, and every module in the patch would pulse.
        if (was.position != now.position ||
            was.params.toList() != now.params.toList() ||
            was.steps.toList() != now.steps.toList()
        ) {
            changed += id
        }
    }

    // A cable that appears or disappears changes both of the modules it touches; only
    // one of them may be visible on screen, so flag each.
    val mine = connections.toSet()
    val theirs = source.connections.toSet()
    ((mine - theirs) + (theirs - mine)).forEach {
        changed += it.from.moduleId
        changed += it.to.moduleId
    }

    return changed
}
