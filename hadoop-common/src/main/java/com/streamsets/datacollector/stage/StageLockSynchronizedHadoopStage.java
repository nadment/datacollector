/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.stage;

import com.streamsets.pipeline.api.Stage;
import org.apache.hadoop.conf.Configuration;

import java.util.List;

public class StageLockSynchronizedHadoopStage<C extends Stage.Context> implements Stage<C> {

  protected final Stage stage;

  protected StageLockSynchronizedHadoopStage(Stage stage) {
    this.stage = stage;
  }

  @Override
  public List<ConfigIssue> init(Info info, C context) {
    synchronized (Stage.class) {
      return stage.init(info, context);
    }
  }

  @Override
  public void destroy() {
    stage.destroy();
  }
}
