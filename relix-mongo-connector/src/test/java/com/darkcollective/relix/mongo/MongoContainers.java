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
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The MongoDB container these tests run against, published no wider than the address
 * the client will actually dial.
 *
 * <p>Testcontainers publishes a container's port on <em>every</em> interface —
 * {@code 0.0.0.0} and {@code [::]}. Nothing here needs that: a test fixture has no
 * business being reachable from the network for as long as it runs.
 *
 * <p><strong>Which address that is, is not always loopback.</strong> This pinned
 * {@code 127.0.0.1} until CI moved onto CodeBuild, where the build itself runs in a
 * container: Testcontainers then resolves the Docker host to the bridge gateway and
 * hands the client {@code mongodb://172.18.0.1:32770}, so a port published on
 * loopback alone is refused. Every Mongo test failed with a 30-second
 * {@code MongoTimeoutException} — the shape that reads like a slow container and is
 * really an unreachable one.
 *
 * <p>It is worth recording how that was misdiagnosed, because the check looked
 * conclusive. A probe on the runner confirmed the daemon was local
 * ({@code DOCKER_HOST} unset), that the loopback publish worked, and that the build
 * could open {@code 127.0.0.1:27017} — all true, and none of it the question. What
 * the client dials is {@link DockerClientFactory#dockerHostIpAddress()}, and only
 * the driver's own error named it.
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

    /**
     * A {@code mongo:7.0} container published only on the address Testcontainers will
     * hand the client — {@code 127.0.0.1} on a developer machine, the Docker bridge
     * gateway where the build is itself containerised.
     */
    static MongoDBContainer hostBound() {
        String bindIp = clientFacingAddress();
        return new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
                .withCreateContainerCmdModifier(cmd -> {
                    var hostConfig = cmd.getHostConfig();
                    if (hostConfig != null) {
                        hostConfig.withPortBindings(new PortBinding(
                                Ports.Binding.bindIp(bindIp), new ExposedPort(MONGO_PORT)));
                    }
                });
    }

    /**
     * The one address the test client uses, as an IP. Resolved because Testcontainers
     * answers {@code "localhost"} on a developer machine and Docker's bind wants an
     * address; an unresolvable answer falls back to loopback, which is the safe
     * direction — a fixture that cannot be reached fails loudly, one published wider
     * than intended does not.
     */
    private static String clientFacingAddress() {
        String host = DockerClientFactory.instance().dockerHostIpAddress();
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }
}
