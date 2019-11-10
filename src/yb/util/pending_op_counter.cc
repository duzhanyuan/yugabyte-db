// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#include "yb/util/pending_op_counter.h"

#include <glog/logging.h>

#include "yb/gutil/strings/substitute.h"

using strings::Substitute;

namespace yb {
namespace util {

uint64_t PendingOperationCounter::Update(uint64_t delta) {
  const uint64_t result = counters_.fetch_add(delta, std::memory_order::memory_order_release);
  VLOG(2) << "[" << this << "] Update(" << static_cast<int64_t>(delta) << "), result = " << result;
  // Ensure that there is no underflow in either counter.
  DCHECK_EQ((result & (1ull << 63)), 0); // Counter of DisableAndWaitForOps() calls.
  DCHECK_EQ((result & (kDisabledDelta >> 1)), 0); // Counter of pending operations.
  return result;
}

// The implementation is based on OperationTracker::WaitForAllToFinish.
Status PendingOperationCounter::WaitForOpsToFinish(const MonoDelta& timeout) {
  const int complain_ms = 1000;
  const MonoTime start_time = MonoTime::Now();
  int64_t num_pending_ops = 0;
  int num_complaints = 0;
  int wait_time_usec = 250;
  while ((num_pending_ops = GetOpCounter()) > 0) {
    const MonoDelta diff = MonoTime::Now() - start_time;
    if (diff > timeout) {
      return STATUS(TimedOut, Substitute(
          "Timed out waiting for all pending operations to complete. "
          "$0 transactions pending. Waited for $1",
          num_pending_ops, diff.ToString()));
    }
    const int64_t waited_ms = diff.ToMilliseconds();
    if (waited_ms / complain_ms > num_complaints) {
      LOG(WARNING) << Substitute("Waiting for $0 pending operations to complete now for $1 ms",
                                 num_pending_ops, waited_ms);
      num_complaints++;
    }
    wait_time_usec = std::min(wait_time_usec * 5 / 4, 1000000);
    SleepFor(MonoDelta::FromMicroseconds(wait_time_usec));
  }
  CHECK_EQ(num_pending_ops, 0) << "Number of pending operations must be 0";

  const MonoTime deadline = start_time + timeout;
  if (PREDICT_FALSE(!disable_.try_lock_until(deadline.ToSteadyTimePoint()))) {
    return STATUS(TimedOut, "Timed out waiting to disable the resource exclusively");
  }

  return Status::OK();
}

}  // namespace util
}  // namespace yb
