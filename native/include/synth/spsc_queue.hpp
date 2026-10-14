#pragma once
#include <atomic>

namespace synth {

// Lock-free single-producer / single-consumer ring, power-of-two capacity.
// Producer = JNI thread, consumer = RtAudio callback thread (PLAN.md §2.1).
template <typename T, unsigned N>
class Spsc {
  static_assert((N & (N - 1)) == 0, "capacity must be a power of two");

 public:
  bool push(const T &v) {
    const unsigned h = head_.load(std::memory_order_relaxed);
    const unsigned next = (h + 1) & (N - 1);
    if (next == tail_.load(std::memory_order_acquire)) return false;  // full
    buf_[h] = v;
    head_.store(next, std::memory_order_release);
    return true;
  }

  bool pop(T &v) {
    const unsigned t = tail_.load(std::memory_order_relaxed);
    if (t == head_.load(std::memory_order_acquire)) return false;  // empty
    v = buf_[t];
    tail_.store((t + 1) & (N - 1), std::memory_order_release);
    return true;
  }

 private:
  T buf_[N]{};
  std::atomic<unsigned> head_{0};
  std::atomic<unsigned> tail_{0};
};

}  // namespace synth
