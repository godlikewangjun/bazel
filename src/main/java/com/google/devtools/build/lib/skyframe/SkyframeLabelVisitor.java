// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.pkgcache.TransitivePackageLoader;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.SkyframeTransitivePackageLoader;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.skyframe.CyclesReporter;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Skyframe-based transitive package loader.
 */
final class SkyframeLabelVisitor implements TransitivePackageLoader {

  private final SkyframeTransitivePackageLoader transitivePackageLoader;
  private final AtomicReference<CyclesReporter> skyframeCyclesReporter;

  SkyframeLabelVisitor(SkyframeTransitivePackageLoader transitivePackageLoader,
      AtomicReference<CyclesReporter> skyframeCyclesReporter) {
    this.transitivePackageLoader = transitivePackageLoader;
    this.skyframeCyclesReporter = skyframeCyclesReporter;
  }

  // The only remaining non-test caller of this code is BlazeQueryEnvironment.
  @Override
  public boolean sync(EventHandler eventHandler, Set<Label> labelsToVisit, boolean keepGoing,
      int parallelThreads) throws InterruptedException {
    EvaluationResult<TransitiveTargetValue> result = transitivePackageLoader.loadTransitiveTargets(
        eventHandler, labelsToVisit, keepGoing, parallelThreads);

    if (!hasErrors(result)) {
      return true;
    }

    Set<Entry<SkyKey, ErrorInfo>> errors = result.errorMap().entrySet();
    if (!keepGoing) {
      // We may have multiple errors, but in non keep_going builds, we're obligated to print only
      // one of them.
      Preconditions.checkState(!errors.isEmpty(), result);
      Entry<SkyKey, ErrorInfo> error = errors.iterator().next();
      ErrorInfo errorInfo = error.getValue();
      SkyKey topLevel = error.getKey();
      Label topLevelLabel = (Label) topLevel.argument();
      if (!Iterables.isEmpty(errorInfo.getCycleInfo())) {
        skyframeCyclesReporter.get().reportCycles(errorInfo.getCycleInfo(), topLevel, eventHandler);
        errorAboutLoadingFailure(topLevelLabel, null, eventHandler);
      } else if (isDirectErrorFromTopLevelLabel(topLevelLabel, labelsToVisit, errorInfo)) {
        // An error caused by a non-top-level label has already been reported during error
        // bubbling but an error caused by the top-level non-target label itself hasn't been
        // reported yet. Note that errors from top-level targets have already been reported
        // during target parsing.
        errorAboutLoadingFailure(topLevelLabel, errorInfo.getException(), eventHandler);
      }
      return false;
    }

    for (Entry<SkyKey, ErrorInfo> errorEntry : errors) {
      SkyKey key = errorEntry.getKey();
      ErrorInfo errorInfo = errorEntry.getValue();
      Preconditions.checkState(key.functionName().equals(SkyFunctions.TRANSITIVE_TARGET), errorEntry);
      Label topLevelLabel = (Label) key.argument();
      if (!Iterables.isEmpty(errorInfo.getCycleInfo())) {
        skyframeCyclesReporter.get().reportCycles(errorInfo.getCycleInfo(), key, eventHandler);
      }
      if (isDirectErrorFromTopLevelLabel(topLevelLabel, labelsToVisit, errorInfo)) {
        // Unlike top-level targets, which have already gone through target parsing,
        // errors directly coming from top-level labels have not been reported yet.
        //
        // See the note in the --nokeep_going case above.
        eventHandler.handle(Event.error(errorInfo.getException().getMessage()));
      }
      warnAboutLoadingFailure(topLevelLabel, eventHandler);
    }
    for (Label topLevelLabel : result.<Label>keyNames()) {
      SkyKey topLevelTransitiveTargetKey = TransitiveTargetValue.key(topLevelLabel);
      TransitiveTargetValue topLevelTransitiveTargetValue = result.get(topLevelTransitiveTargetKey);
      if (topLevelTransitiveTargetValue.getTransitiveRootCauses() != null) {
        warnAboutLoadingFailure(topLevelLabel, eventHandler);
      }
    }
    return false;
  }

  private static boolean hasErrors(EvaluationResult<TransitiveTargetValue> result) {
    if (result.hasError()) {
      return true;
    }
    for (TransitiveTargetValue transitiveTargetValue : result.values()) {
      if (transitiveTargetValue.getTransitiveRootCauses() != null) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDirectErrorFromTopLevelLabel(Label label, Set<Label> topLevelLabels,
      ErrorInfo errorInfo) {
    return errorInfo.getException() != null && topLevelLabels.contains(label)
        && Iterables.contains(errorInfo.getRootCauses(), TransitiveTargetValue.key(label));
  }

  private static void errorAboutLoadingFailure(Label topLevelLabel, @Nullable Throwable throwable,
      EventHandler eventHandler) {
    eventHandler.handle(Event.error(
        "Loading of target '" + topLevelLabel + "' failed; build aborted" +
            (throwable == null ? "" : ": " + throwable.getMessage())));
  }

  private static void warnAboutLoadingFailure(Label label, EventHandler eventHandler) {
    eventHandler.handle(Event.warn("errors encountered while loading target '" + label + "'"));
  }
}
