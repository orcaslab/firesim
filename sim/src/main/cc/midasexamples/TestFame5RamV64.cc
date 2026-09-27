// See LICENSE for license details.

#include "Fame5RamTest.h"

class TestFame5RamV64 final : public Fame5RamTest<64> {
public:
  using Fame5RamTest<64>::Fame5RamTest;
};

TEST_MAIN(TestFame5RamV64)
