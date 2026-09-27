// See LICENSE for license details.

#ifndef MIDASEXAMPLES_FAME5GPUTEST_H
#define MIDASEXAMPLES_FAME5GPUTEST_H

#include "TestHarness.h"

#include <cstdio>
#include <cstdlib>
#include <gmp.h>

// Runs the FAME-5 mini-GPU prototype for a fixed number of target cycles and
// prints every core's checksum and output count, unpacked from the wide
// sums/counts ports. Threaded and unthreaded builds must print identical
// "F5 ..." lines.
template <size_t N>
class Fame5GpuTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    const char *env = getenv("FAME5_CYCLES");
    uint32_t cycles = env ? strtoul(env, nullptr, 0) : 4000;
    target_reset();
    step(cycles);
    mpz_t sums, counts, word;
    mpz_inits(sums, counts, word, NULL);
    peek("io_sums", true); // waits for stable outputs; the wide peek below does not
    peek("io_sums", sums);
    peek("io_counts", counts);
    uint64_t total = 0;
    for (size_t i = 0; i < N; i++) {
      mpz_tdiv_q_2exp(word, sums, 32 * i);
      uint32_t s = mpz_get_ui(word) & 0xffffffffu;
      mpz_tdiv_q_2exp(word, counts, 32 * i);
      uint32_t c = mpz_get_ui(word) & 0xffffffffu;
      total += c;
      printf("F5 core %zu sum 0x%08x count %u\n", i, s, c);
    }
    printf("F5 total %llu outputs after %u cycles\n",
           (unsigned long long)total, cycles);
    mpz_clears(sums, counts, word, NULL);
  }
};

#endif // MIDASEXAMPLES_FAME5GPUTEST_H
