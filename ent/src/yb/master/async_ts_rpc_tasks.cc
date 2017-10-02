// Copyright (c) YugaByte, Inc.

#include "yb/master/async_ts_rpc_tasks.h"

#include "yb/util/logging.h"
#include "yb/master/master.h"
#include "yb/master/catalog_manager.h"
#include "yb/master/ts_descriptor.h"

namespace yb {
namespace master {
namespace enterprise {

using std::shared_ptr;

Status RetryingTSRpcTask::ResetTSProxy() {
  RETURN_NOT_OK(super::ResetTSProxy());

  return Status::OK();
}

} // namespace enterprise
} // namespace master
} // namespace yb