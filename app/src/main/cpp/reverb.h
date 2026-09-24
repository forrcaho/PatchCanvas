#pragma once

#include <array>
#include <cmath>
#include <cstdint>

/**
 * Two reverbs, for ReverbNode to choose between by ear: a room and a plate.
 *
 * Both are written here from their published descriptions rather than vendored. DaisySP's
 * ReverbSc lives in DaisySP-LGPL, which this project keeps out on purpose (see the vendor
 * README), and the two designs below are the two that have been listened to most: Jezar's
 * Freeverb, which he placed in the public domain, and the plate Jon Dattorro set out in "Effect
 * Design, Part 1" (JAES, 1997). Neither is a line-for-line port of anyone's code.
 *
 * Every buffer is a fixed array sized for 96kHz, so nothing is allocated after the node is
 * made -- on the interface's thread, like every node -- and prepare() only works out lengths.
 * Every value written back into a loop is flushed below 1e-20: a dying tail otherwise spends
 * a long time in denormal numbers, which some processors take hundreds of cycles over, for a
 * sound nobody can hear.
 */
namespace reverb {

/** Below anything audible and above a float's denormal range. */
inline float flush(float x) { return std::fabs(x) < 1e-20f ? 0.0f : x; }

/** A delay line of at most N samples (a power of two), read [n] samples back from the newest. */
template <int32_t N>
struct Ring {
    static_assert((N & (N - 1)) == 0, "a power of two, so wrapping is a mask");
    std::array<float, N> data{};
    int32_t at = 0;

    void write(float x) {
        at = (at + 1) & (N - 1);
        data[static_cast<std::size_t>(at)] = x;
    }
    /** The sample written [n] writes ago; 0 is the newest. */
    float read(int32_t n) const { return data[static_cast<std::size_t>((at - n) & (N - 1))]; }
    /** Between two samples, linearly: for a length that moves. */
    float read(float n) const {
        const auto whole = static_cast<int32_t>(n);
        const float part = n - static_cast<float>(whole);
        const float a = read(whole);
        return a + (read(whole + 1) - a) * part;
    }
};

/**
 * An allpass of a fixed length in a Ring: `(g + z^-L) / (1 + g z^-L)`, which passes every
 * frequency at the same level and smears it in time. What it keeps in its line is readable,
 * because Dattorro's plate takes output taps from inside two of them.
 */
template <int32_t N>
struct Allpass {
    Ring<N> line;
    int32_t length = 1;

    float process(float in, float g) {
        const float delayed = line.read(length - 1);
        const float w = flush(in - g * delayed);
        line.write(w);
        return delayed + g * w;
    }
    float processModulated(float in, float g, float length) {
        const float delayed = line.read(length - 1.0f);
        const float w = flush(in - g * delayed);
        line.write(w);
        return delayed + g * w;
    }
};

/** Scales a length given at [reference] Hz to [rate], never below one sample or past [most]. */
inline int32_t scaled(int32_t samples, float rate, float reference, int32_t most) {
    const auto n = static_cast<int32_t>(std::lround(samples * rate / reference));
    return n < 1 ? 1 : (n > most ? most : n);
}

/**
 * Freeverb: eight damped combs in parallel and four allpasses in series, per channel, the right
 * channel's lengths 23 samples longer than the left's so the two decorrelate. Tunings are
 * Jezar's, at 44.1kHz, scaled to the stream's rate.
 */
class Room {
public:
    static constexpr int32_t kCombs = 8;
    static constexpr int32_t kAllpasses = 4;

    void prepare(float rate) {
        static constexpr int32_t kComb[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static constexpr int32_t kAll[kAllpasses] = {556, 441, 341, 225};
        constexpr int32_t kSpread = 23;
        for (int32_t i = 0; i < kCombs; ++i) {
            left_[i].length = scaled(kComb[i], rate, 44100.0f, kCombRing - 1);
            right_[i].length = scaled(kComb[i] + kSpread, rate, 44100.0f, kCombRing - 1);
        }
        for (int32_t i = 0; i < kAllpasses; ++i) {
            leftAll_[i].length = scaled(kAll[i], rate, 44100.0f, kAllRing - 1);
            rightAll_[i].length = scaled(kAll[i] + kSpread, rate, 44100.0f, kAllRing - 1);
        }
    }

    /** [size] 0..1 is the combs' feedback from 0.7 to 0.98; [damp] 0..1 their high-cut. */
    void set(float size, float damp) {
        feedback_ = 0.7f + 0.28f * size;
        damp_ = 0.4f * damp;
    }

    void process(float in, float &outL, float &outR) {
        // Freeverb's fixed input gain: eight combs sum, and this keeps their sum near unity.
        const float x = in * 0.015f;
        float l = 0.0f;
        float r = 0.0f;
        for (int32_t i = 0; i < kCombs; ++i) {
            l += left_[i].process(x, feedback_, damp_);
            r += right_[i].process(x, feedback_, damp_);
        }
        for (int32_t i = 0; i < kAllpasses; ++i) {
            l = leftAll_[i].process(l);
            r = rightAll_[i].process(r);
        }
        outL = l;
        outR = r;
    }

private:
    static constexpr int32_t kCombRing = 4096; // 1640 samples at 44.1kHz is 3570 at 96kHz
    static constexpr int32_t kAllRing = 2048;  // 579 is 1261

    /** A comb with a one-pole low-pass in its loop: Freeverb's "lowpass-feedback comb". */
    struct Comb {
        Ring<kCombRing> line;
        int32_t length = 1;
        float store = 0.0f;
        float process(float in, float feedback, float damp) {
            const float out = line.read(length - 1);
            store = flush(out * (1.0f - damp) + store * damp);
            line.write(flush(in + store * feedback));
            return out;
        }
    };
    /** Freeverb's allpass, which is not a true one -- Jezar's own, kept for its sound. */
    struct Diffuser {
        Ring<kAllRing> line;
        int32_t length = 1;
        float process(float in) {
            const float delayed = line.read(length - 1);
            line.write(flush(in + delayed * 0.5f));
            return delayed - in;
        }
    };

    std::array<Comb, kCombs> left_{};
    std::array<Comb, kCombs> right_{};
    std::array<Diffuser, kAllpasses> leftAll_{};
    std::array<Diffuser, kAllpasses> rightAll_{};
    float feedback_ = 0.84f;
    float damp_ = 0.2f;
};

/**
 * Dattorro's plate: the input band-limited and smeared by four allpasses, then fed into a
 * figure-of-eight "tank" of two halves, each a modulated allpass, a delay, a damping low-pass,
 * another allpass and another delay, each half feeding the other. The outputs are taps taken
 * from all over the tank, added and subtracted, which is what makes the two channels differ.
 * Lengths are the paper's, at 29761Hz, scaled to the stream's rate.
 */
class Plate {
public:
    void prepare(float rate) {
        rate_ = rate;
        constexpr float kRef = 29761.0f;
        inputs_[0].length = scaled(142, rate, kRef, kInRing - 1);
        inputs_[1].length = scaled(107, rate, kRef, kInRing - 1);
        inputs_[2].length = scaled(379, rate, kRef, kInRing - 1);
        inputs_[3].length = scaled(277, rate, kRef, kInRing - 1);
        leftMod_.length = scaled(672, rate, kRef, kModRing - 64);
        rightMod_.length = scaled(908, rate, kRef, kModRing - 64);
        leftA_.length = scaled(4453, rate, kRef, kTankRing - 1);
        rightA_.length = scaled(4217, rate, kRef, kTankRing - 1);
        leftAll_.length = scaled(1800, rate, kRef, kTankRing - 1);
        rightAll_.length = scaled(2656, rate, kRef, kTankRing - 1);
        leftB_.length = scaled(3720, rate, kRef, kTankRing - 1);
        rightB_.length = scaled(3163, rate, kRef, kTankRing - 1);
        excursion_ = 16.0f * rate / kRef;
        // About one cycle a second, the two halves a quarter-cycle apart: slow enough to
        // read as a shimmer rather than a vibrato, and the paper's own suggestion.
        lfoStep_ = 2.0f * static_cast<float>(M_PI) * 1.0f / rate;
        const auto tap = [&](int32_t n) { return scaled(n, rate, kRef, kTankRing - 1); };
        tapsL_ = {tap(266), tap(2974), tap(1913), tap(1996), tap(1990), tap(187), tap(1066)};
        tapsR_ = {tap(353), tap(3627), tap(1228), tap(2673), tap(2111), tap(335), tap(121)};
    }

    /** [size] 0..1 is the tank's decay from 0.25 to 0.97; [damp] 0..1 its high-cut. */
    void set(float size, float damp) {
        decay_ = 0.25f + 0.72f * size;
        damping_ = 0.8f * damp;
        // The paper ties the second diffusion to the decay, so a short plate stays clear.
        const float d2 = decay_ + 0.15f;
        diffusion2_ = d2 < 0.25f ? 0.25f : (d2 > 0.5f ? 0.5f : d2);
    }

    void process(float in, float &outL, float &outR) {
        // Band-limit, then smear: 0.75 on the first two, 0.625 on the last two.
        bandwidth_ = flush(0.9995f * in + 0.0005f * bandwidth_);
        float x = inputs_[0].process(bandwidth_, 0.75f);
        x = inputs_[1].process(x, 0.75f);
        x = inputs_[2].process(x, 0.625f);
        x = inputs_[3].process(x, 0.625f);

        lfo_ += lfoStep_;
        if (lfo_ > 2.0f * static_cast<float>(M_PI)) lfo_ -= 2.0f * static_cast<float>(M_PI);
        const float wobbleL = excursion_ * (1.0f + std::sin(lfo_));
        const float wobbleR = excursion_ * (1.0f + std::cos(lfo_));

        // Each half takes the input plus the other half's last output, decayed.
        float l = x + decay_ * lastR_;
        float r = x + decay_ * lastL_;

        l = leftMod_.processModulated(l, -0.7f, static_cast<float>(leftMod_.length) + wobbleL);
        leftA_.write(flush(l));
        l = leftA_.read(leftA_.length - 1);
        dampL_ = flush(l * (1.0f - damping_) + dampL_ * damping_);
        l = leftAll_.process(dampL_ * decay_, diffusion2_);
        leftB_.write(flush(l));
        lastL_ = leftB_.read(leftB_.length - 1);

        r = rightMod_.processModulated(r, -0.7f, static_cast<float>(rightMod_.length) + wobbleR);
        rightA_.write(flush(r));
        r = rightA_.read(rightA_.length - 1);
        dampR_ = flush(r * (1.0f - damping_) + dampR_ * damping_);
        r = rightAll_.process(dampR_ * decay_, diffusion2_);
        rightB_.write(flush(r));
        lastR_ = rightB_.read(rightB_.length - 1);

        // The paper's taps: each channel mostly from the far half of the tank, less its own.
        outL = 0.6f * (rightA_.read(tapsL_[0]) + rightA_.read(tapsL_[1]) -
                       rightAll_.line.read(tapsL_[2]) + rightB_.read(tapsL_[3]) -
                       leftA_.read(tapsL_[4]) - leftAll_.line.read(tapsL_[5]) -
                       leftB_.read(tapsL_[6]));
        outR = 0.6f * (leftA_.read(tapsR_[0]) + leftA_.read(tapsR_[1]) -
                       leftAll_.line.read(tapsR_[2]) + leftB_.read(tapsR_[3]) -
                       rightA_.read(tapsR_[4]) - rightAll_.line.read(tapsR_[5]) -
                       rightB_.read(tapsR_[6]));
    }

private:
    static constexpr int32_t kInRing = 2048;    // 379 at 29761Hz is 1223 at 96kHz
    static constexpr int32_t kModRing = 4096;   // 908 plus its wobble is about 3000
    static constexpr int32_t kTankRing = 16384; // 4453 is 14364

    /** A plain delay in the tank, which keeps its own length beside its line. */
    struct Delay : Ring<kTankRing> {
        int32_t length = 1;
    };

    std::array<Allpass<kInRing>, 4> inputs_{};
    Allpass<kModRing> leftMod_;
    Allpass<kModRing> rightMod_;
    Delay leftA_;
    Delay rightA_;
    Allpass<kTankRing> leftAll_;
    Allpass<kTankRing> rightAll_;
    Delay leftB_;
    Delay rightB_;
    std::array<int32_t, 7> tapsL_{};
    std::array<int32_t, 7> tapsR_{};

    float rate_ = 48000.0f;
    float bandwidth_ = 0.0f;
    float dampL_ = 0.0f;
    float dampR_ = 0.0f;
    float lastL_ = 0.0f;
    float lastR_ = 0.0f;
    float lfo_ = 0.0f;
    float lfoStep_ = 0.0f;
    float excursion_ = 26.0f;
    float decay_ = 0.61f;
    float damping_ = 0.4f;
    float diffusion2_ = 0.5f;
};

} // namespace reverb
