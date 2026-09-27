// See LICENSE for license details.

#include "Fame5GpuTest.h"

class TestFame5GpuV64 final : public Fame5GpuTest<64> {
public:
  using Fame5GpuTest<64>::Fame5GpuTest;
};

TEST_MAIN(TestFame5GpuV64)
