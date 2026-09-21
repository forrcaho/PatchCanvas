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
 * The one place a note becomes a pitch for everything that sounds one -- the synths here,
 * and the SoundFont player, which has voices of its own and needs the same answer.
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
 * Notes in, sound out: one note at a time, and everything a synth does that is not the
 * sound itself.
 *
 * Every synth here is monophonic, which is the point of the poly subpatch rather than a
 * limitation beside it. Polyphony used to live inside each synth -- eight voices, one
 * envelope shared between them, nothing per note reachable from outside -- and that is the
 * thing the redesign replaced. A voice is a patch now: an Osc, an Env, an Amp and whatever
 * else, inside a Poly the engine stamps out per note. Leaving eight voices in here as well
 * would be two allocators stacked on one another, the inner one never choosing anything,
 * and the whole design surface it was meant to retire still present.
 *
 * What is left is what one note needs and PolyIn does not do: resolving a degree to a pitch
 * against the scale of its beat, matching an Off to its On by source as well as id, gliding
 * on a Change, and releasing when the source is unpatched. Each of those was paid for by a
 * bug found on the phone, which is why they are here once rather than in each synth.
 *
 * A Voice provides:
 *
 *   void  init(float sampleRate)
 *   void  strike(float hz, float velocity, bool stolen)  // a note starts
 *   void  setFreq(float hz)                              // a glide moved it
 *   float render(bool gate, bool &finished)              // one sample
 *
 * render() sets [finished] once the voice has nothing left to say -- its gate ramp has run
 * out, or a plucked string has rung out -- and it is free from then on. Which of those it is
 * is the voice's business, because only it knows what its silence looks like.
 *
 * A template rather than a virtual call, since render() runs once per sample.
 */
template <typename Voice>
class MonoSynth : public Node {
public:
    int32_t inputCount() const override { return 1; }  // notes
    int32_t outputCount() const override { return 1; }
    uint32_t noteInputs() const override { return 1u << 0; }

    void prepare(int32_t sampleRate) override {
        Node::prepare(sampleRate);
        glideFrames_ = std::max(1, static_cast<int32_t>(0.03f * static_cast<float>(sampleRate)));
        voice_.init(static_cast<float>(sampleRate));
    }

    void notesCut(int32_t port, int32_t source) override {
        (void) port; // one note input, so there is nothing to tell apart
        if (source_ == source) gate_ = false;
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

            if (!active_) {
                // Stepped by nothing while it is free: an oscillator that is not
                // accumulating phase is one that starts its next note from zero.
                o[i] = 0.0f;
                continue;
            }

            if (glideLeft_ > 0) {
                const float t = 1.0f - static_cast<float>(glideLeft_ - 1) /
                                               static_cast<float>(glideFrames_);
                const float eased = t * t * (3.0f - 2.0f * t);
                octaves_ = glideFrom_ + (glideTo_ - glideFrom_) * eased;
                voice_.setFreq(kMiddleC * std::exp2(octaves_));
                --glideLeft_;
            }
            bool finished = false;
            const float sample = voice_.render(gate_, finished);
            if (finished) {
                // Free rather than merely quiet, and said by the voice -- see above.
                active_ = false;
                gate_ = false;
                source_ = -1;
                id_ = 0;
                o[i] = 0.0f;
                continue;
            }
            o[i] = sample;
        }
    }

    /** Whether it is sounding or still releasing. For tests. */
    bool inUse() const { return active_; }

protected:
    /** The voice, for a knob that changes its sound. */
    Voice &voice() { return voice_; }

private:
    void start(const NoteEvent &event) {
        // A note arriving over one still held takes the voice, which is what monophonic
        // means. [stolen] says so, and each voice decides what that costs it: an Osc keeps
        // its gate ramp open rather than dropping to silence and back, and an FM leaves its
        // phases running rather than restarting them mid-cycle.
        const bool stolen = active_ && gate_;

        // Resolved here, against the scale of the beat the note started on, which traveled
        // with it. It is not resolved again unless the source sends a Change: a sequencer's
        // note keeps the pitch it started on, and only a drone's follows the scale.
        octaves_ = pitchOf(event);
        glideLeft_ = 0;
        id_ = event.id;
        source_ = event.source;
        gate_ = true;
        active_ = true;
        voice_.strike(kMiddleC * std::exp2(octaves_),
                      std::min(std::max(event.velocity, 0.0f), 1.0f), stolen);
    }

    void release(uint32_t id, int32_t source) {
        // Both, because ids belong to the source that chose them: two sequencers patched to
        // one synth are each counting from one. And only the note actually sounding, so an
        // Off arriving after its note was taken does not cut the note that took it.
        if (gate_ && id_ == id && source_ == source) gate_ = false;
    }

    /** A held note told to move: it glides there rather than stepping. */
    void change(const NoteEvent &event) {
        if (!active_ || id_ != event.id || source_ != event.source) return;
        const float target = pitchOf(event);
        if (glideLeft_ == 0 && octaves_ == target) return;
        // A glide, not a step. Retuning a sounding voice was rejected once because a major
        // third dropping to a minor third mid-note is a step with no ramp -- the transient
        // every crossfade here exists to prevent. The ramp is the answer to that, over the
        // same 30ms and the same smoothstep the crossfades use. A glide already under way
        // starts again from wherever it has got to.
        glideFrom_ = octaves_;
        glideTo_ = target;
        glideLeft_ = glideFrames_;
    }

    float pitchOf(const NoteEvent &event) const { return ::pitchOf(event, scales_); }

    /** How long a glide takes: 30ms, like every crossfade in the engine. */
    int32_t glideFrames_ = 1440;

    Voice voice_;
    /** Whose note it is: the id its On carried, and the input slot that sent it. */
    uint32_t id_ = 0;
    int32_t source_ = -1;
    bool gate_ = false;
    /**
     * Taken, whether or not it is making a sound yet.
     *
     * Separate from the voice's own idea of running, because a gate ramp only becomes
     * running once a sample has been processed.
     */
    bool active_ = false;
    /** Where its pitch is now, in octaves from middle C, cents included. */
    float octaves_ = 0.0f;
    /** A glide in progress: from, to, and frames still to go. */
    float glideFrom_ = 0.0f;
    float glideTo_ = 0.0f;
    int32_t glideLeft_ = 0;
};
