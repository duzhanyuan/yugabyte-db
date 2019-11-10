#!/usr/bin/env bash

# Run the given command in the given directory and with the given PATH. This is invoked on a remote
# host using ssh during the distributed C++ build.

#
# The following only applies to changes made to this file as part of YugaByte development.
#
# Portions Copyright (c) YugaByte, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied.  See the License for the specific language governing permissions and limitations
# under the License.
#
work_dir=$1

cd "$work_dir"
if [[ $? -ne 0 ]]; then
  echo "Failed to set current directory to '$work_dir'" >&2
  exit 1
fi

if [[ -n ${2:-} ]]; then
  export PATH=$2
fi
shift 2
exec "$@"
