#pragma once

#include <array>
#include <atomic>
#include <cstddef>

/**
 * Single-producer, single-consumer ring buffer.
 *
 * The UI thread pushes, the audio thread pops, and neither ever blocks the other.
 * Capacity is fixed at construction because the audio side must not allocate, and it
 * is a power of two so the wrap is a mask rather than a division.
 *
 * The memory ordering is the standard pair: the producer releases its slot write
 * before publishing the new write index, and the consumer acquires that index before
 * reading the slot. Everything else can be relaxed, because each side is the only
 * writer of its own index.
 */
template <typename T, std::size_t Capacity>
class SpscQueue {
    static_assert(Capacity >= 2, "capacity must be at least 2");
    static_assert((Capacity & (Capacity - 1)) == 0, "capacity must be a power of two");

public:
    /** Producer side. False means full -- the caller decides what that means. */
    bool push(const T &item) {
        const std::size_t write = write_.load(std::memory_order_relaxed);
        const std::size_t next = (write + 1) & kMask;
        if (next == read_.load(std::memory_order_acquire)) {
            return false; // full
        }
        slots_[write] = item;
        write_.store(next, std::memory_order_release);
        return true;
    }

    /** Consumer side. False means empty. */
    bool pop(T &out) {
        const std::size_t read = read_.load(std::memory_order_relaxed);
        if (read == write_.load(std::memory_order_acquire)) {
            return false; // empty
        }
        out = slots_[read];
        read_.store((read + 1) & kMask, std::memory_order_release);
        return true;
    }

private:
    static constexpr std::size_t kMask = Capacity - 1;

    std::array<T, Capacity> slots_{};
    // Kept apart so the two indices do not share a cache line and make each side's
    // writes visible as contention on the other's reads.
    alignas(64) std::atomic<std::size_t> write_{0};
    alignas(64) std::atomic<std::size_t> read_{0};
};
