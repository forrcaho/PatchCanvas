#pragma once

#include <cstdint>

/**
 * Notes, as events rather than voltages.
 *
 * This is where "it is all voltage" stops applying. Audio and CV stay exactly as they
 * are -- a buffer of samples, advisory typing, any output into any input -- because a
 * cable carrying a signal really is interchangeable with any other. A note is not: it
 * starts, it ends, and something has to know which end belongs to which start. So note
 * ports are typed, and the interface refuses to patch one to a signal input.
 *
 * Not MIDI. Bespoke uses MIDI messages internally because people plug MIDI devices into
 * computers; nobody does that to a phone often enough to shape the core around it. MIDI
 * in, if it ever lands, translates at the edge.
 */

/**
 * Events one port may carry in one block.
 *
 * A block is 32 frames, so the worst case that is still music is a sixteen-note column
 * ending and the next one starting inside the same block: sixteen offs and sixteen ons,
 * which is exactly this. Beyond it push() refuses, and a refused Off would hang a voice
 * -- which is why the cap is the honest worst case rather than a round number, and why
 * a voice is also released when its source is unpatched. Costs 4KB a node across every
 * port, against 512 bytes for the sample buffers.
 */
constexpr int32_t kMaxNoteEvents = 32;

/**
 * Change is reserved and unimplemented: it is per-note expression, what MPE does by
 * giving each note its own MIDI channel. Here the id already does the channel's job, so
 * reserving the kind now keeps a touch keyboard from being a format change later.
 */
enum class NoteKind : uint8_t { On = 0, Off = 1, Change = 2 };

struct NoteEvent {
    /**
     * Chosen by the source; an Off finds its note by this.
     *
     * MIDI matches a note-off to its note by pitch, which is untenable once pitch is
     * continuous -- it means comparing floats for equality -- and awkward even without
     * that. Two notes at the same pitch are two notes.
     */
    uint32_t id = 0;
    /** Step in the scale sounding on [beat], running past the period in either direction. */
    int32_t degree = 0;
    /**
     * The whole beat the note starts on, worked out in integers by whatever made it.
     *
     * Carried rather than recomputed downstream. Which scale a note sounds in is decided
     * from a tick's count and never from the transport's floating position, and only the
     * clocked node that emitted the event knows that count -- so the decision travels
     * with the note instead of being taken again, differently, by the voice.
     */
    int64_t beat = 0;
    /** Against the degree: glide, bend, pitch tracking, and every transpose control. */
    float cents = 0.0f;
    float velocity = 1.0f;
    /** Sample within the block. */
    uint16_t offset = 0;
    NoteKind kind = NoteKind::On;
    /**
     * Which of the destination's sources emitted this, filled in by the graph as it
     * merges them. Meaningless on an output buffer, where nothing has merged yet.
     *
     * A note input takes several sources, and each of them picks its own ids from one --
     * so two sequencers patched to the same voice would collide on every id without
     * this. It is a source's slot, not its position in the list, so unpatching one does
     * not silently renumber the notes another still has sounding.
     */
    uint8_t source = 0;
};

/**
 * A note output's events for one block, filled during its node's process() and read by
 * whatever it feeds, in topological order -- exactly as a sample buffer is.
 *
 * Cleared by its producer at the top of process(), which is the one way this differs
 * from a sample buffer: those persist deliberately, so a feedback edge reads what its
 * source left last block. Events persisting would mean playing them again forever. A
 * note cable on a back edge still delivers one block late, for the same reason and by
 * the same mechanism -- the consumer reads the buffer before the producer clears it.
 */
struct NoteBuffer {
    NoteEvent events[kMaxNoteEvents] = {};
    int32_t count = 0;

    void clear() { count = 0; }

    bool push(const NoteEvent &event) {
        if (count >= kMaxNoteEvents) return false;
        events[count++] = event;
        return true;
    }
};
