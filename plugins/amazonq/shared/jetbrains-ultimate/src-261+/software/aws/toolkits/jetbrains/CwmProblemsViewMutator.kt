// Copyright 2025 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow

// BackendToolWindowHost was removed in 2026.1 (261) — no-op until replacement API is identified
class CwmProblemsViewMutator : ProblemsViewMutator {
    override fun mutateProblemsView(project: Project, runnable: (ToolWindow) -> Unit) {
    }
}
