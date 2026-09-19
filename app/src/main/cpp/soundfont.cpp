#include "soundfont.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "tsf.h"

extern "C" int tsf_preset_bank(const tsf *f, int preset);
extern "C" int tsf_preset_number(const tsf *f, int preset);

namespace {

/**
 * TinySoundFont's own level, unchanged. Measured against the shipped bank: one note at
 * full velocity peaks between 0.4 (an electric piano) and 1.3 (a square lead), which is an
 * Osc's range. 12dB was tried first, on a guess that a GM bank voiced for sixteen summed
 * channels would be quiet; it peaked at 3.4.
 */
constexpr float kGainDb = 0.0f;

/** A note's pitch as a fractional MIDI key: middle C is 60 in both worlds. */
float midiOf(float octaves) { return 60.0f + 12.0f * octaves; }

} // namespace

// ---------------------------------------------------------------- SoundFont

SoundFont *SoundFont::load(const void *data, int32_t size) {
    tsf *font = tsf_load_memory(data, size);
    if (font == nullptr) return nullptr;
    if (tsf_get_presetcount(font) <= 0) {
        tsf_close(font);
        return nullptr;
    }
    return new SoundFont(font);
}

SoundFont::~SoundFont() { tsf_close(font_); }

int32_t SoundFont::presetCount() const { return tsf_get_presetcount(font_); }

const char *SoundFont::presetName(int32_t index) const { return tsf_get_presetname(font_, index); }

int32_t SoundFont::presetBank(int32_t index) const { return tsf_preset_bank(font_, index); }

int32_t SoundFont::presetProgram(int32_t index) const { return tsf_preset_number(font_, index); }

// ---------------------------------------------------------------- SoundFontSynth

SoundFontSynth::SoundFontSynth(const SoundFont &font) {
    synth = tsf_copy(font.font());
    if (synth == nullptr) return;
    tsf_set_max_voices(synth, kVoices);
    tsf_set_output(synth, TSF_MONO, 48000, kGainDb);
    // The last channel first, so the array is allocated once at its full size rather than
    // grown sixteen times.
    for (int32_t c = kChannels - 1; c >= 0; --c) tsf_channel_set_presetindex(synth, c, 0);
}

SoundFontSynth::~SoundFontSynth() { tsf_close(synth); }

// ---------------------------------------------------------------- SfNode

SfNode::~SfNode() { delete synth_; }

void SfNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    glideFrames_ = std::max(1, static_cast<int32_t>(0.03f * static_cast<float>(sampleRate)));
    if (synth_ != nullptr && synth_->synth != nullptr) {
        tsf_set_output(synth_->synth, TSF_MONO, sampleRate, kGainDb);
    }
}

void SfNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0:
            preset_ = static_cast<int32_t>(std::max(0.0f, value) + 0.5f);
            applyPreset();
            break;
        case 1: level_ = std::min(std::max(value, 0.0f), 2.0f); break;
        default: break;
    }
}

void SfNode::applyPreset() {
    if (synth_ == nullptr || synth_->synth == nullptr) return;
    tsf *s = synth_->synth;
    // A preset the font does not have plays its first one rather than nothing: a patch
    // saved against one bank and opened against another still makes a sound.
    int32_t index = tsf_get_presetindex(s, preset_ / 128, preset_ % 128);
    if (index < 0) index = 0;
    // Every channel, sounding or not. A held note keeps the preset it started with --
    // TinySoundFont binds a voice to its preset when it starts -- and the next note
    // takes the new one.
    for (int32_t c = 0; c < SoundFontSynth::kChannels; ++c) tsf_channel_set_presetindex(s, c, index);
}

Resource *SfNode::swapResource(Resource *incoming) {
    // Only ever sent a SoundFontSynth; the interface is the one sender.
    auto *previous = synth_;
    synth_ = static_cast<SoundFontSynth *>(incoming);
    if (synth_ != nullptr && synth_->synth != nullptr) {
        tsf_set_output(synth_->synth, TSF_MONO, sampleRate_, kGainDb);
    }
    applyPreset();
    // The notes the old synth was holding went with it; the ones still held start again on
    // this one, on the channels they already had.
    if (synth_ != nullptr && synth_->synth != nullptr) {
        for (int32_t c = 0; c < SoundFontSynth::kChannels; ++c) {
            const Channel &channel = channels_[c];
            if (!channel.gate) continue;
            tsf_channel_set_tuning(synth_->synth, c, channel.midi - static_cast<float>(channel.key));
            tsf_channel_note_on(synth_->synth, c, channel.key, channel.velocity);
        }
    }
    return previous;
}

void SfNode::start(const NoteEvent &event) {
    // The oldest channel with nothing held, and only then the oldest held one. A released
    // note may still be ringing on its channel, and its tail takes the new note's tuning --
    // so the channel reused is the one released longest ago, whose tail is most likely
    // over.
    Channel *chosen = nullptr;
    int32_t chosenIndex = 0;
    for (int32_t pass = 0; pass < 2 && chosen == nullptr; ++pass) {
        for (int32_t c = 0; c < SoundFontSynth::kChannels; ++c) {
            Channel &channel = channels_[c];
            if (pass == 0 && channel.gate) continue;
            if (chosen == nullptr || channel.age < chosen->age) {
                chosen = &channel;
                chosenIndex = c;
            }
        }
    }
    // Null while the font loads: the note is kept, and struck when a synth arrives.
    tsf *s = synth_ != nullptr ? synth_->synth : nullptr;
    if (chosen->gate && s != nullptr) tsf_channel_note_off(s, chosenIndex, chosen->key);

    const float midi = midiOf(pitchOf(event, scales_));
    const int32_t key = std::min(127, std::max(0, static_cast<int32_t>(std::lround(midi))));
    chosen->id = event.id;
    chosen->source = event.source;
    chosen->key = key;
    chosen->gate = true;
    chosen->age = age_++;
    chosen->midi = midi;
    chosen->glideLeft = 0;
    // The key is the nearest one, and the channel's tuning is everything else -- which
    // is also how a pitch past either end of the keyboard is still reached.
    chosen->velocity = std::max(std::min(std::max(event.velocity, 0.0f), 1.0f), 1.0f / 127.0f);
    if (s == nullptr) return;
    tsf_channel_set_tuning(s, chosenIndex, midi - static_cast<float>(key));
    tsf_channel_note_on(s, chosenIndex, key, chosen->velocity);
}

void SfNode::release(uint32_t id, int32_t source) {
    for (int32_t c = 0; c < SoundFontSynth::kChannels; ++c) {
        Channel &channel = channels_[c];
        if (channel.gate && channel.id == id && channel.source == source) {
            if (synth_ != nullptr && synth_->synth != nullptr) tsf_channel_note_off(synth_->synth, c, channel.key);
            channel.gate = false;
        }
    }
}

void SfNode::change(const NoteEvent &event) {
    const float target = midiOf(pitchOf(event, scales_));
    for (auto &channel : channels_) {
        if (!channel.gate || channel.id != event.id || channel.source != event.source) continue;
        // A glide, as an Osc's is: over 30ms, smoothstepped -- here a block at a time,
        // since a channel's tuning is set, not ramped.
        if (synth_ == nullptr || synth_->synth == nullptr) {
            // Nothing sounding to glide: it simply is there when a synth arrives.
            channel.midi = target;
            return;
        }
        channel.glideFrom = channel.midi;
        channel.glideTo = target;
        channel.glideLeft = glideFrames_;
        return;
    }
}

void SfNode::notesCut(int32_t port, int32_t source) {
    (void) port;
    for (int32_t c = 0; c < SoundFontSynth::kChannels; ++c) {
        Channel &channel = channels_[c];
        if (channel.gate && channel.source == source) {
            if (synth_ != nullptr && synth_->synth != nullptr) tsf_channel_note_off(synth_->synth, c, channel.key);
            channel.gate = false;
        }
    }
}

int32_t SfNode::notesHeld() const {
    int32_t n = 0;
    for (const auto &channel : channels_) n += channel.gate ? 1 : 0;
    return n;
}

void SfNode::render(float *into, int32_t frames) {
    if (frames > 0) tsf_render_float(synth_->synth, into, frames, 0);
}

void SfNode::process(int32_t frames) {
    float *o = out(0);
    const NoteBuffer &notes = notesIn(0);
    if (synth_ == nullptr || synth_->synth == nullptr) {
        // Nothing to play them on yet, but they are kept: see the class comment.
        for (int32_t i = 0; i < notes.count; ++i) {
            const NoteEvent &event = notes.events[i];
            if (event.kind == NoteKind::On) start(event);
            if (event.kind == NoteKind::Off) release(event.id, event.source);
            if (event.kind == NoteKind::Change) change(event);
        }
        std::memset(o, 0, static_cast<size_t>(frames) * sizeof(float));
        return;
    }
    tsf *s = synth_->synth;

    for (int32_t c = 0; c < SoundFontSynth::kChannels; ++c) {
        Channel &channel = channels_[c];
        if (channel.glideLeft <= 0) continue;
        channel.glideLeft = std::max(0, channel.glideLeft - frames);
        const float t = 1.0f - static_cast<float>(channel.glideLeft) / static_cast<float>(glideFrames_);
        const float eased = t * t * (3.0f - 2.0f * t);
        channel.midi = channel.glideFrom + (channel.glideTo - channel.glideFrom) * eased;
        tsf_channel_set_tuning(s, c, channel.midi - static_cast<float>(channel.key));
    }

    // Rendered in pieces between events, so a note starts on its own sample the way it
    // does in every other synth here.
    int32_t done = 0;
    for (int32_t i = 0; i < notes.count; ++i) {
        const NoteEvent &event = notes.events[i];
        const int32_t at = std::min(static_cast<int32_t>(event.offset), frames);
        if (at > done) {
            render(o + done, at - done);
            done = at;
        }
        if (event.kind == NoteKind::On) start(event);
        if (event.kind == NoteKind::Off) release(event.id, event.source);
        if (event.kind == NoteKind::Change) change(event);
    }
    render(o + done, frames - done);

    const float from = applied_;
    for (int32_t i = 0; i < frames; ++i) {
        const float gain = from + (level_ - from) * static_cast<float>(i + 1) / static_cast<float>(frames);
        o[i] *= gain;
    }
    applied_ = level_;
}
