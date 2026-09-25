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
 * One field of one row: the sealed {@link com.darkcollective.relix.value.Value}
 * hierarchy.
 *
 * <p>Strings, numbers, booleans and NULL; the four temporal kinds
 * ({@link com.darkcollective.relix.value.DateValue},
 * {@link com.darkcollective.relix.value.TimeValue},
 * {@link com.darkcollective.relix.value.TimestampValue},
 * {@link com.darkcollective.relix.value.DurationValue}); and the nested
 * {@link com.darkcollective.relix.value.StructValue} and
 * {@link com.darkcollective.relix.value.ArrayValue}. The hierarchy is sealed, so a
 * {@code switch} over a value is exhaustive.
 */
package com.darkcollective.relix.value;
