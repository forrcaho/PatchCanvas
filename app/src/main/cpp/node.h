#pragma once

#include <array>
#include <cstdint>

#include "transport.h"

/** Frames processed per inner block. 96-frame bursts divide by this exactly. */
constexpr int32_t kBlockSize = 32;
constexpr int32_t kMaxPorts = 4;
constexpr int32_t kMaxParams = 4;

/**
 * A graph node.
 *
 * Outputs are owned buffers; inputs are borrowed pointers into whatever upstream node
 * produced them, rewired by the graph before each block. Nothing here allocates after
 * construction, and construction never happens on the audio thread.
 *
 * Output buffers are deliberately NOT cleared between blocks. That is what makes a
 * feedback edge cost exactly one block of delay: a node whose source is evaluated after
 * it simply reads the buffer the source left behind last time.
 */
class Node {
public:
    virtual ~Node() = default;

    virtual int32_t inputCount() const = 0;
    virtual int32_t outputCount() const = 0;

    virtual void prepare(int32_t sampleRate) { sampleRate_ = sampleRate; }

    /**
     * A knob moved. Values arrive in real units -- hertz, seconds, beats per minute --
     * rather than normalised, because the range and the curve belong to the thing being
     * described and the interface should be able to say "440 Hz" rather than "0.63".
     *
     * Called from applyCommands on the audio thread, so an implementation may compute
     * coefficients but must not allocate.
     */
    virtual void setParam(int32_t index, float value) {
        (void) index;
        (void) value;
    }
    /**
     * One step of a sequence changed.
     *
     * Separate from setParam because a pattern is not a knob: kMaxParams is 4, which is
     * the right size for the controls a panel shows and nowhere near a sequence. Pitch
     * arrives in octaves, like every other pitch that crosses this boundary, so a scale
     * is a table on the other side and nothing here needs to know about tuning.
     *
     * Audio thread, same rules as setParam.
     */
    virtual void setStep(int32_t index, float pitch, bool gate) {
        (void) index;
        (void) pitch;
        (void) gate;
    }

    /**
     * Where a sequencer has got to, or -1 for everything that is not one.
     *
     * Read on the audio thread only, by the graph, which republishes it through an
     * atomic the interface can see. Nothing outside the audio thread calls this.
     */
    virtual int32_t position() const { return -1; }

    /** How often the transport ticks this node, or none for a node it does not drive. */
    virtual Interval interval() const { return {}; }

    /**
     * A boundary of interval() falls at sample [offset] of the block about to be processed.
     *
     * [count] is which boundary, counted from the transport's beat zero, so a sequencer
     * can take its step from where the transport is rather than counting ticks it has
     * seen -- and stopping, resetting and changing length all land where the position
     * says. Delivered by the graph before process(), on the audio thread.
     *
     * One entry point on purpose: a pulse cable, if one is ever built, calls the same
     * thing with a count of its own.
     */
    virtual void tick(int32_t offset, int64_t count) {
        (void) offset;
        (void) count;
    }

    /**
     * Set by the graph before every block: how far one frame moves the transport, and
     * whether it is moving at all. Zero beats per frame while stopped, so anything timed
     * in beats holds still with it.
     */
    void setTiming(double beatsPerFrame, bool running) {
        beatsPerFrame_ = beatsPerFrame;
        running_ = running;
    }

    virtual void process(int32_t frames) = 0;

    void setInput(int32_t port, const float *buffer) { inputs_[port] = buffer; }
    const float *output(int32_t port) const { return outputs_[port].data(); }

protected:
    const float *input(int32_t port) const { return inputs_[port]; }
    float *out(int32_t port) { return outputs_[port].data(); }

    int32_t sampleRate_ = 48000;
    /** See setTiming. */
    double beatsPerFrame_ = 0.0;
    bool running_ = false;

private:
    std::array<const float *, kMaxPorts> inputs_{};
    std::array<std::array<float, kBlockSize>, kMaxPorts> outputs_{};
};
