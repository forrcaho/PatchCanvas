#pragma once

#include <cstdint>

#include "node.h"
#include "synth.h"

struct tsf;

/**
 * A SoundFont file, parsed once and shared by every SF node that plays it.
 *
 * Parsing a bank means converting every sample to float, which for GeneralUser GS is
 * seconds of work and about twice the file in memory -- so it happens once per file, on a
 * background thread, and the nodes playing it each get a SoundFontSynth copy that shares
 * the samples. Lives as long as the interface keeps its handle, which is the process.
 */
class SoundFont {
public:
    /** Null for anything TinySoundFont will not read, SF3 included. */
    static SoundFont *load(const void *data, int32_t size);
    ~SoundFont();

    int32_t presetCount() const;
    const char *presetName(int32_t index) const;
    int32_t presetBank(int32_t index) const;
    int32_t presetProgram(int32_t index) const;
    tsf *font() const { return font_; }

private:
    explicit SoundFont(tsf *font) : font_(font) {}
    tsf *font_;
};

/**
 * One SF node's own synth: its voices and channels, over the font's shared samples.
 *
 * Built on the interface's thread, where everything TinySoundFont allocates lazily is
 * allocated up front -- the voices capped, every channel touched -- so nothing it does on
 * the audio thread allocates. Handed to the node as a Resource, and freed back on the
 * interface's thread when replaced.
 */
class SoundFontSynth : public Resource {
public:
    static constexpr int32_t kChannels = 16;
    /** Voices, not notes: a note can layer several regions, a stereo sample two. */
    static constexpr int32_t kVoices = 64;

    explicit SoundFontSynth(const SoundFont &font);
    ~SoundFontSynth() override;

    tsf *synth = nullptr;
};

/**
 * A SoundFont player: notes in, sound out.
 *
 * Not a MonoSynth, because TinySoundFont has voices of its own and renders them all at
 * once. What this adds is the engine's idea of pitch. A SoundFont thinks in MIDI keys; a
 * note here is a degree of whatever scale is sounding. So each note gets a channel of its
 * own, is played on the nearest key, and the channel's tuning -- fractional semitones --
 * carries the rest. A 19-TET degree lands where it should, and a Change glides the channel.
 *
 * Silent until its synth arrives: the font loads in the background, and the node exists
 * from the moment the module does. It keeps count of its notes all the same, and strikes
 * whatever is held when a synth arrives -- found on the emulator, where a drone's chord,
 * sent in the same sync that made the node, reached it a quarter of a second before its
 * font and was never heard. Changing font re-strikes the chord the same way.
 *
 * Knobs, mirroring PatchCanvas.kt: preset (bank * 128 + program, chosen from the panel's
 * header) and level.
 */
class SfNode : public Node {
public:
    ~SfNode() override;

    int32_t inputCount() const override { return 1; }  // notes
    int32_t outputCount() const override { return 1; }
    uint32_t noteInputs() const override { return 1u << 0; }
    void prepare(int32_t sampleRate) override;
    void setParam(int32_t index, float value) override;
    Resource *swapResource(Resource *incoming) override;
    void notesCut(int32_t port, int32_t source) override;
    void process(int32_t frames) override;

    /** Channels holding a note, for tests. */
    int32_t notesHeld() const;

private:
    struct Channel {
        uint32_t id = 0;
        int32_t source = -1;
        int32_t key = 0;
        float velocity = 1.0f;
        bool gate = false;
        int64_t age = 0;
        /** Where its pitch is, as a fractional MIDI key, and a glide towards another. */
        float midi = 60.0f;
        float glideFrom = 0.0f;
        float glideTo = 0.0f;
        int32_t glideLeft = 0;
    };

    void start(const NoteEvent &event);
    void release(uint32_t id, int32_t source);
    void change(const NoteEvent &event);
    void applyPreset();
    void render(float *into, int32_t frames);

    SoundFontSynth *synth_ = nullptr;
    int32_t preset_ = 0;
    float level_ = 1.0f;
    /** The level last applied, ramped from across a block so a moved knob never steps. */
    float applied_ = 1.0f;
    Channel channels_[SoundFontSynth::kChannels];
    int64_t age_ = 0;
    int32_t glideFrames_ = 1440;
};
