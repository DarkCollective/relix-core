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
/**
 * The engine's JSON form of a heading, shared by the logical and physical plan writers.
 *
 * <p>Exported only to {@code relix-plan}, which is the other writer's module. It names
 * {@code relix-json}'s writer, which is the engine's own and not API, so it is kept off
 * the published surface.
 */
package com.darkcollective.relix.semantic.json;
