// See LICENSE for license details.

#ifndef __EMUL_MM_H
#define __EMUL_MM_H

#include "core/config.h"

#include <cstring>
#include <deque>
#include <queue>
#include <stdint.h>

#include "core/config.h"

class mm_t {
public:
  mm_t(const AXI4Config conf)
      : conf(conf), word_size(conf.beat_bytes()), data(nullptr), size(0) {}

  virtual void init(size_t sz, int lsz);

  virtual bool ar_ready() = 0;
  virtual bool aw_ready() = 0;
  virtual bool w_ready() = 0;
  virtual bool b_valid() = 0;
  virtual uint64_t b_resp() = 0;
  virtual uint64_t b_id() = 0;
  virtual bool r_valid() = 0;
  virtual uint64_t r_resp() = 0;
  virtual uint64_t r_id() = 0;
  virtual void *r_data() = 0;
  virtual bool r_last() = 0;

  virtual void tick(bool reset,

                    bool ar_valid,
                    uint64_t ar_addr,
                    uint64_t ar_id,
                    uint64_t ar_size,
                    uint64_t ar_len,

                    bool aw_valid,
                    uint64_t aw_addr,
                    uint64_t aw_id,
                    uint64_t aw_size,
                    uint64_t aw_len,

                    bool w_valid,
                    uint64_t w_strb,
                    const std::vector<uint32_t> &w_data,
                    bool w_last,

                    bool r_ready,
                    bool b_ready) = 0;

  virtual void *get_data() { return data; }
  virtual size_t get_size() { return size; }
  virtual size_t get_word_size() { return word_size; }
  virtual size_t get_line_size() { return line_size; }

  void write(uint64_t addr, const uint8_t *data, uint64_t strb, uint64_t size);
  std::vector<char> read(uint64_t addr);

  virtual ~mm_t();

  void load_mem(unsigned long start, const char *fname);

  const AXI4Config &get_config() const { return conf; }

protected:
  const AXI4Config conf;

  int word_size;
  uint8_t *data;
  size_t size;
  int line_size;
};

struct mm_rresp_t {
  uint64_t id = 0;
  std::vector<char> data;
  bool last = false;

  mm_rresp_t() = default;

  mm_rresp_t(uint64_t id, const std::vector<char> &data, bool last)
      : id(id), data(data), last(last) {}
};

class mm_magic_t final : public mm_t {
public:
  mm_magic_t(const AXI4Config &conf) : mm_t(conf), store_inflight(false) {}

  void init(size_t sz, int lsz) override;

  bool ar_ready() override { return true; }
  bool aw_ready() override { return !store_inflight; }
  bool w_ready() override { return store_inflight; }
  bool b_valid() override { return !bresp.empty(); }
  uint64_t b_resp() override { return 0; }
  uint64_t b_id() override { return b_valid() ? bresp.front() : 0; }
  bool r_valid() override { return cur_beat() != nullptr; }
  uint64_t r_resp() override { return 0; }
  uint64_t r_id() override { return r_valid() ? cur_beat()->id : 0; }
  void *r_data() override {
    return r_valid() ? const_cast<char *>(&cur_beat()->data[0]) : &dummy_data[0];
  }
  bool r_last() override { return r_valid() ? cur_beat()->last : false; }

  void tick(bool reset,

            bool ar_valid,
            uint64_t ar_addr,
            uint64_t ar_id,
            uint64_t ar_size,
            uint64_t ar_len,

            bool aw_valid,
            uint64_t aw_addr,
            uint64_t aw_id,
            uint64_t aw_size,
            uint64_t aw_len,

            bool w_valid,
            uint64_t w_strb,
            const std::vector<uint32_t> &w_data,
            bool w_last,

            bool r_ready,
            bool b_ready) override;

protected:
  bool store_inflight;
  uint64_t store_addr;
  uint64_t store_id;
  uint64_t store_size;
  uint64_t store_count;
  std::vector<char> dummy_data;
  std::queue<uint64_t> bresp;

  std::queue<mm_rresp_t> rresp;

  // Out-of-order responses, for exercising a design against memory that
  // behaves like a real interconnect rather than an ideal one. Set by
  // FIRESIM_MM_REORDER: 0 (default) returns every burst whole and in order;
  // 1 returns bursts of different IDs in random order; 2 also interleaves
  // their beats. Same-ID bursts always keep their order, as AXI requires.
  // FIRESIM_MM_LATENCY delays every burst by that many cycles.
  struct burst_t {
    uint64_t ready_cycle;
    std::deque<mm_rresp_t> beats;
  };
  int reorder_mode = 0;
  uint64_t latency = 0;
  std::deque<burst_t> bursts;
  long selected = -1;
  bool eligible(size_t i) const;
  void select_burst();
  const mm_rresp_t *cur_beat() const {
    if (reorder_mode == 0) {
      return rresp.empty() ? nullptr : &rresp.front();
    }
    return selected < 0 ? nullptr : &bursts[selected].beats.front();
  }

  uint64_t cycle;
};

#endif // __EMUL_MM_H
