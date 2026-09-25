/*
 * Copyright 2026 Darkcollective, LLC
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
package com.darkcollective.relix.connectors.std.internal;

/**
 * A connector that reads a file named by its connection, and so is handed that file by
 * {@link FileResolver} rather than resolving it itself.
 *
 * <p>Package-private on purpose: every connector that wants this lives here, and no
 * out-of-tree connector has asked for it (#1054).
 */
interface FileBacked {
}
