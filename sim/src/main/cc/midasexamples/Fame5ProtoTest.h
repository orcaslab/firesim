// See LICENSE for license details.

#ifndef MIDASEXAMPLES_FAME5PROTOTEST_H
#define MIDASEXAMPLES_FAME5PROTOTEST_H

#include "TestHarness.h"

#include <cstdio>
#include <cstdlib>
#include <string>

// Runs the FAME-5 prototype for a fixed number of target cycles and prints
// every core's checksum and output count. Threaded and unthreaded builds of
// the same design must print identical lines ("F5 ..."), which the caller diffs.
template <size_t N>
class Fame5ProtoTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    const char *env = getenv("FAME5_CYCLES");
    uint32_t cycles = env ? strtoul(env, nullptr, 0) : 4000;
    target_reset();
    step(cycles);
    uint64_t total = 0;
    for (size_t i = 0; i < N; i++) {
      uint32_t s = peek("io_sums_" + std::to_string(i));
      uint32_t c = peek("io_counts_" + std::to_string(i));
      total += c;
      printf("F5 core %zu sum 0x%08x count %u\n", i, s, c);
    }
    printf("F5 total %llu outputs after %u cycles\n",
           (unsigned long long)total, cycles);
  }
};

#endif // MIDASEXAMPLES_FAME5PROTOTEST_H
