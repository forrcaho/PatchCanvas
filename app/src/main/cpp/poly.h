#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "node.h"

/**
 * Pitch is 1V/oct in the Eurorack sense, expressed in octaves: 0 is middle C, 1.0 is an
 * octave up. Using octaves rather than volts keeps the arithmetic to exp2 and avoids
 * pretending there is a voltage anywhere in here.
 */
constexpr float kMiddleC = 261.6256f;

/**
 * A note's pitch in octaves from middle C, against the scale of the beat it carries.
 *
 * The one place a note becomes a pitch for everything that sounds one -- the voices here,
 * and the SoundFont player, which has its own voices and needs the same answer.
 */
inline float pitchOf(const NoteEvent &event, const ScaleList *scales) {
    // The engine still never learns what a semitone is: a table lookup and an exp2.
    const float octaves = scales != nullptr ? scales->tableAt(event.beat).octavesOf(event.degree)
                                            : ScaleTable{}.octavesOf(event.degree);
    return octaves + event.cents / 1200.0f;
}

/**
 * A note's on/off with the click taken off it, and nothing else.
 *
 * What is left of the envelope on a synth that no longer has one. Shaping a note is an
 * Env's job now, inside a poly subpatch where there is one Env per note -- an envelope
 * built into a synth can only be the same envelope for every voice, which is what made
 * "patch an Env to FM's index" impossible and started the redesign. What a synth still
 * owes is not sounding a step when a gate opens or closes, and that is this.
 *
 * Five milliseconds, smoothstepped so the slope is zero at both ends as well as the value
 * -- the same reason the graph's crossfades are smoothstepped rather than linear. Long
 * enough that the edge is not broadband, short enough that a staccato sixteenth still
 * sounds staccato. It is not 30ms like a crossfade because a crossfade happens under a
 * note you are already playing and this *is* the note starting.
 */
struct GateRamp {
    void init(float sampleRate) {
        step_ = 1.0f / std::max(1.0f, 0.005f * sampleRate);
        position_ = 0.0f;
    }

    /**
     * One sample of gain, 0 to 1. [finished] once a released note has reached silence --
     * which is also the whole of "this voice is free", since there is nothing else left
     * ringing.
     *
     * Nothing restarts the ramp. A voice that finished is already at zero, so a fresh note
     * rises from silence; a voice stolen while still sounding carries on from where it is,
     * up if it was held and back up if it was releasing, because dropping it to zero first
     * is precisely the step this exists to avoid.
     */
    float process(bool gate, bool &finished) {
        position_ = gate ? std::min(1.0f, position_ + step_) : std::max(0.0f, position_ - step_);
        if (!gate && position_ <= 0.0f) finished = true;
        return position_ * position_ * (3.0f - 2.0f * position_);
    }

private:
    float step_ = 1.0f / 240.0f;
    /** 0 to 1, linear; the gain returned is the smoothstep of it. */
    float position_ = 0.0f;
};

/**
 * Notes in, sound out, with the voices inside it: everything a polyphonic synth does that
 * is not the sound itself.
 *
 * Which voice a note takes, which one is stolen, matching an Off to its On by source as
 * well as id, gliding a held note on a Change, and releasing what an unpatched source was
 * holding -- all of it was written once, for the oscillator, and every one of those rules
 * was paid for by a bug found on the phone or by a test. A second synth copying it would
 * have been a second place to fix the next one. So a synth here is a [Voice] -- one note's
 * worth of sound -- and this template is the rest.
 *
 * A Voice provides:
 *
 *   void  init(float sampleRate)
 *   void  strike(float hz, float velocity, bool stolen)  // a note starts on this voice
 *   void  setFreq(float hz)                              // a glide moved it
 *   float render(bool gate, bool &finished)              // one sample
 *
 * render() sets [finished] once the voice has nothing left to say -- its release is over,
 * or a plucked string has rung out -- and the voice is free from then on. Which of those
 * it is is the voice's business, because only it knows what its silence looks like: an
 * envelope's amplitude passes through zero on its way *up*, so "quiet" and "done" are not
 * the same question.
 *
 * A template rather than a virtual call, since render() runs once per voice per sample.
 */
template <typename Voice, int32_t Voices>
class PolySynth : public Node {
public:
    static constexpr int32_t kVoices = Voices;

    int32_t inputCount() const override { return 1; }  // notes
    int32_t outputCount() const override { return 1; }
    uint32_t noteInputs() const override { return 1u << 0; }

    void prepare(int32_t sampleRate) override {
        Node::prepare(sampleRate);
        glideFrames_ = std::max(1, static_cast<int32_t>(0.03f * static_cast<float>(sampleRate)));
        for (auto &slot : slots_) slot.voice.init(static_cast<float>(sampleRate));
    }

    void notesCut(int32_t port, int32_t source) override {
        (void) port; // one note input, so there is nothing to tell apart
        for (auto &slot : slots_) {
            if (slot.source == source) slot.gate = false;
        }
    }

    void process(int32_t frames) override {
        float *o = out(0);
        const NoteBuffer &notes = notesIn(0);
        int32_t next = 0;

        for (int32_t i = 0; i < frames; ++i) {
            // Events land on their own sample, the way a tick does. Already in offset
            // order, merged that way by the graph.
            while (next < notes.count && notes.events[next].offset <= i) {
                const NoteEvent &event = notes.events[next];
                if (event.kind == NoteKind::On) start(event);
                if (event.kind == NoteKind::Off) release(event.id, event.source);
                if (event.kind == NoteKind::Change) change(event);
                ++next;
            }

            // Summed, not averaged, like Mix: a chord is louder than one note, which is
            // true of every instrument, and Out's limiter catches what that costs at the
            // top.
            float sum = 0.0f;
            for (auto &slot : slots_) {
                // A free voice is stepped by nothing: an oscillator that is not
                // accumulating phase is one that starts its next note from zero.
                if (!slot.active) continue;

                if (slot.glideLeft > 0) {
                    const float t = 1.0f - static_cast<float>(slot.glideLeft - 1) /
                                                   static_cast<float>(glideFrames_);
                    const float eased = t * t * (3.0f - 2.0f * t);
                    slot.octaves = slot.glideFrom + (slot.glideTo - slot.glideFrom) * eased;
                    slot.voice.setFreq(kMiddleC * std::exp2(slot.octaves));
                    --slot.glideLeft;
                }
                bool finished = false;
                const float sample = slot.voice.render(slot.gate, finished);
                if (finished) {
                    // Free rather than merely quiet, and said by the voice -- see above.
                    slot.active = false;
                    slot.gate = false;
                    slot.source = -1;
                    slot.id = 0;
                    continue;
                }
                sum += sample;
            }
            o[i] = sum;
        }
    }

    /** How many voices are taken: sounding, or still releasing. */
    int32_t voicesInUse() const {
        int32_t n = 0;
        for (const auto &slot : slots_) n += slot.active ? 1 : 0;
        return n;
    }

protected:
    /** Every voice, sounding or not, for a knob that applies to all of them. */
    template <typename F>
    void forEachVoice(F f) {
        for (auto &slot : slots_) f(slot.voice);
    }

private:
    struct Slot {
        Voice voice;
        /** Who it belongs to: the id its On carried, and the input slot that sent it. */
        uint32_t id = 0;
        int32_t source = -1;
        bool gate = false;
        /**
         * Taken, whether or not it is making a sound yet.
         *
         * Separate from the voice's own idea of running, because an envelope only becomes
         * running once a sample has been processed -- and every note of a chord starts on
         * the same sample, before any of them has. Asking the envelope instead handed the
         * whole chord to voice zero, one note overwriting the next, which sounded exactly
         * like a monophonic sequencer and was found by a test asserting three notes sound.
         */
        bool active = false;
        /** When it started, for choosing which to steal. */
        int64_t age = 0;
        /** Where its pitch is now, in octaves from middle C, cents included. */
        float octaves = 0.0f;
        /** A glide in progress: from, to, and frames still to go. */
        float glideFrom = 0.0f;
        float glideTo = 0.0f;
        int32_t glideLeft = 0;
    };

    void start(const NoteEvent &event) {
        // A note takes an idle voice, then the oldest one already released, and only then
        // steals one that is still held. Stealing a held voice restarts it mid-cycle,
        // which is a click -- so it is the last resort rather than the rule, and by the
        // time every voice is held the next note was going to cost something regardless.
        Slot *chosen = nullptr;
        for (auto &slot : slots_) {
            if (!slot.active) { chosen = &slot; break; }
        }
        if (chosen == nullptr) {
            for (auto &slot : slots_) {
                if (slot.gate) continue;
                if (chosen == nullptr || slot.age < chosen->age) chosen = &slot;
            }
        }
        if (chosen == nullptr) {
            for (auto &slot : slots_) {
                if (chosen == nullptr || slot.age < chosen->age) chosen = &slot;
            }
        }
        const bool stolen = chosen->active && chosen->gate;

        // Resolved here, against the scale of the beat the note started on, which traveled
        // with it. It is not resolved again unless the source sends a Change: a
        // sequencer's note keeps the pitch it started on, and only a drone's follows the
        // scale.
        chosen->octaves = pitchOf(event);
        chosen->glideLeft = 0;
        chosen->id = event.id;
        chosen->source = event.source;
        chosen->gate = true;
        chosen->active = true;
        chosen->age = age_++;
        chosen->voice.strike(kMiddleC * std::exp2(chosen->octaves),
                             std::min(std::max(event.velocity, 0.0f), 1.0f), stolen);
    }

    void release(uint32_t id, int32_t source) {
        for (auto &slot : slots_) {
            // Both, because ids belong to the source that chose them: two sequencers
            // patched to the same input are each counting from one.
            if (slot.gate && slot.id == id && slot.source == source) slot.gate = false;
        }
    }

    /** A held note told to move: it glides there rather than stepping. */
    void change(const NoteEvent &event) {
        const float target = pitchOf(event);
        for (auto &slot : slots_) {
            if (!slot.active || slot.id != event.id || slot.source != event.source) continue;
            if (slot.glideLeft == 0 && slot.octaves == target) return;
            // A glide, not a step. Retuning a sounding voice was rejected once because a
            // major third dropping to a minor third mid-note is a step with no ramp -- the
            // transient every crossfade here exists to prevent. The ramp is the answer to
            // that, over the same 30ms and the same smoothstep the crossfades use. A glide
            // already under way starts again from wherever it has got to.
            slot.glideFrom = slot.octaves;
            slot.glideTo = target;
            slot.glideLeft = glideFrames_;
            return;
        }
    }

    float pitchOf(const NoteEvent &event) const { return ::pitchOf(event, scales_); }

    /** How long a glide takes: 30ms, like every crossfade in the engine. */
    int32_t glideFrames_ = 1440;
    Slot slots_[Voices];
    int64_t age_ = 0;
};
