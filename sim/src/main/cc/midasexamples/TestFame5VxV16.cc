// See LICENSE for license details.

#include "Fame5GpuTest.h"

class TestFame5VxV16 final : public Fame5GpuTest<16> {
public:
  using Fame5GpuTest<16>::Fame5GpuTest;
};

TEST_MAIN(TestFame5VxV16)
