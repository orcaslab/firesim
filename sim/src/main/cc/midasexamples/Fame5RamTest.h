// See LICENSE for license details.

#ifndef MIDASEXAMPLES_FAME5RAMTEST_H
#define MIDASEXAMPLES_FAME5RAMTEST_H

#include "TestHarness.h"

#include <cstdio>
#include <cstdlib>
#include <gmp.h>

// Runs the FAME-5 RAM prototype and prints, per core, the checksum of each of
// its seven VX_dp_ram instances (a..g) and of their mix. Threaded and
// unthreaded builds must print identical "F5 ..." lines.
template <size_t N>
class Fame5RamTest : public TestHarness {
public:
  using TestHarness::TestHarness;

  void run_test() override {
    const char *env = getenv("FAME5_CYCLES");
    uint32_t cycles = env ? strtoul(env, nullptr, 0) : 4000;
    target_reset();
    mpz_t sums, word;
    mpz_inits(sums, word, NULL);
    // FAME5_TRACE=<n>: core 0's checksums after each of the first n cycles.
    const char *tr = getenv("FAME5_TRACE");
    uint32_t traced = tr ? strtoul(tr, nullptr, 0) : 0;
    for (uint32_t c = 0; c < traced && c < cycles; c++) {
      step(1);
      peek("io_sums", true); // waits for stable outputs; the wide peek below does not
    peek("io_sums", sums);
      printf("F5 trace %u", c + 1);
      for (size_t k = 0; k < 8; k++) {
        mpz_tdiv_q_2exp(word, sums, 32 * k);
        printf(" %08lx", mpz_get_ui(word) & 0xfffffffful);
      }
      printf("\n");
    }
    step(cycles - (traced < cycles ? traced : cycles));
    peek("io_sums", true); // waits for stable outputs; the wide peek below does not
    peek("io_sums", sums);
    // Up to 8 cores: eight checksums each; beyond: one fold of them per core.
    const size_t per = N <= 8 ? 8 : 1;
    for (size_t i = 0; i < N; i++) {
      printf("F5 core %zu", i);
      for (size_t k = 0; k < per; k++) {
        mpz_tdiv_q_2exp(word, sums, 32 * (per * i + k));
        printf(" %c=%08lx", per == 1 ? 'f' : k < 7 ? 'a' + (int)k : 'x', mpz_get_ui(word) & 0xfffffffful);
      }
      printf("\n");
    }
    printf("F5 done after %u cycles\n", cycles);
    mpz_clears(sums, word, NULL);
  }
};

#endif // MIDASEXAMPLES_FAME5RAMTEST_H
