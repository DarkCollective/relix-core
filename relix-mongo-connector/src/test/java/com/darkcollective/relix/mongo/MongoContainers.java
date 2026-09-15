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
package com.darkcollective.relix.mongo;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The MongoDB container these tests run against, published on the loopback
 * interface only.
 *
 * <p>Testcontainers publishes a container's port on <em>every</em> interface —
 * {@code 0.0.0.0} and {@code [::]}. Nothing here needs that: the test talks to the
 * container over localhost, and a test fixture has no business being reachable from
 * the network for as long as it runs.
 *
 * <p>Note what this is <em>not</em> for. It was first written to stop a macOS
 * permission prompt, on the theory that the all-interfaces bind caused it; the
 * prompt turned out to come from Docker's credential helper, and is dealt with in
 * this module's build file. The binding is kept because it is right on its own
 * terms, not because it fixes that.
 *
 * <p>Docker's {@code "ip": "127.0.0.1"} daemon default does not achieve this under
 * Docker Desktop, whose host-side port forwarder binds all interfaces regardless —
 * measured on macOS 26 with Desktop 29.6.2, where a plain
 * {@code docker run -p 61236:27017} still came back {@code 0.0.0.0:61236} after a
 * confirmed restart, while an explicit {@code -p 127.0.0.1:61235:27017} bound
 * loopback. So the binding is set explicitly here.
 */
final class MongoContainers {

    private static final int MONGO_PORT = 27017;

    private MongoContainers() {
    }

    /** A {@code mongo:7.0} container whose port is published to 127.0.0.1 only. */
    static MongoDBContainer loopbackBound() {
        return new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
                .withCreateContainerCmdModifier(cmd -> {
                    var hostConfig = cmd.getHostConfig();
                    if (hostConfig != null) {
                        hostConfig.withPortBindings(new PortBinding(
                                Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(MONGO_PORT)));
                    }
                });
    }
}
