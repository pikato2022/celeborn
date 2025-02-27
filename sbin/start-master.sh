#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Starts the rss master on the machine this script is executed on.

if [ -z "${RSS_HOME}" ]; then
  export RSS_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

. "${RSS_HOME}/sbin/rss-config.sh"

if [ "$RSS_MASTER_MEMORY" = "" ]; then
  RSS_MASTER_MEMORY="1g"
fi

export RSS_JAVA_OPTS="-Xmx$RSS_MASTER_MEMORY $RSS_MASTER_JAVA_OPTS"

"${RSS_HOME}/sbin/rss-daemon.sh" start org.apache.celeborn.service.deploy.master.Master 1 "$@"