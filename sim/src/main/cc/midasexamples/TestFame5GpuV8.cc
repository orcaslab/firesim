// See LICENSE for license details.

#include "Fame5GpuTest.h"

class TestFame5GpuV8 final : public Fame5GpuTest<8> {
public:
  using Fame5GpuTest<8>::Fame5GpuTest;
};

TEST_MAIN(TestFame5GpuV8)
