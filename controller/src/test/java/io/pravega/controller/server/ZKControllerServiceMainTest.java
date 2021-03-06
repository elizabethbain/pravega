/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.server;

import io.pravega.controller.store.client.StoreClient;
import io.pravega.test.common.TestingServerStarter;
import io.pravega.controller.store.client.ZKClientConfig;
import io.pravega.controller.store.client.impl.StoreClientConfigImpl;
import io.pravega.controller.store.client.impl.ZKClientConfigImpl;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.test.TestingServer;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.Assert.assertEquals;

/**
 * ZK store based ControllerServiceMain tests.
 */
@Slf4j
public class ZKControllerServiceMainTest extends ControllerServiceMainTest {
    private TestingServer zkServer;

    public ZKControllerServiceMainTest() {
        super(false);
    }

    @Override
    public void setup() {
        try {
            zkServer = new TestingServerStarter().start();
        } catch (Exception e) {
            log.error("Error starting test zk server");
            Assert.fail("Error starting test zk server");
        }

        ZKClientConfig zkClientConfig = ZKClientConfigImpl.builder().connectionString(zkServer.getConnectString())
                .initialSleepInterval(500)
                .maxRetries(10)
                .sessionTimeoutMs(10 * 1000)
                .namespace("pravega/" + UUID.randomUUID())
                .build();
        storeClientConfig = StoreClientConfigImpl.withZKClient(zkClientConfig);
    }

    @Override
    public void tearDown() {
        try {
            zkServer.close();
        } catch (IOException e) {
            log.error("Error stopping test zk server");
            Assert.fail("Error stopping test zk server");
        }
    }
    
    static class MockControllerServiceStarter extends ControllerServiceStarter {
        @Getter
        private final CompletableFuture<Void> signalShutdownStarted;
        @Getter
        private final CompletableFuture<Void> waitingForShutdownSignal;

        MockControllerServiceStarter(ControllerServiceConfig serviceConfig, StoreClient storeClient,
                                     CompletableFuture<Void> signalShutdownStarted, CompletableFuture<Void> waitingForShutdownSignal) {
            super(serviceConfig, storeClient);
            this.signalShutdownStarted = signalShutdownStarted;
            this.waitingForShutdownSignal = waitingForShutdownSignal;
        }

        @Override
        protected void startUp() { }

        @Override
        protected void shutDown() throws Exception {
            signalShutdownStarted.complete(null);
            waitingForShutdownSignal.join();
        }
    }

    @Test(timeout = 100000)
    public void testZKSessionExpiry() throws Exception {
        CompletableFuture<Void> signalShutdownStarted = new CompletableFuture<>();
        CompletableFuture<Void> waitingForShutdownSignal = new CompletableFuture<>();

        ConcurrentLinkedQueue<StoreClient> clientQueue = new ConcurrentLinkedQueue<>();
        ControllerServiceMain controllerServiceMain = new ControllerServiceMain(createControllerServiceConfig(),
                (x, y) -> {
                    clientQueue.add(y);
                    return new MockControllerServiceStarter(x, y, signalShutdownStarted, waitingForShutdownSignal);
                });

        controllerServiceMain.startAsync();

        try {
            controllerServiceMain.awaitRunning();
        } catch (IllegalStateException e) {
            log.error("Failed waiting for controllerServiceMain to get ready", e);
            Assert.fail("Failed waiting for controllerServiceMain to get ready");
        }

        MockControllerServiceStarter controllerServiceStarter = (MockControllerServiceStarter) controllerServiceMain.awaitServiceStarting();
        try {
            controllerServiceStarter.awaitRunning();
        } catch (IllegalStateException e) {
            log.error("Failed waiting for controllerServiceStarter to get ready", e);
            Assert.fail("Failed waiting for controllerServiceStarter to get ready");
            return;
        }

        assertEquals(1, clientQueue.size());
        CuratorFramework curatorClient = (CuratorFramework) clientQueue.poll().getClient();
        // Simulate ZK session timeout
        // we will submit zk session expiration and 
        CompletableFuture.runAsync(() -> {
            try {
                curatorClient.getZookeeperClient().getZooKeeper().getTestable().injectSessionExpiration();
            } catch (Exception e) {
                log.error("Failed while simulating client session expiry", e);
                Assert.fail("Failed while simulating client session expiry");
            }
        });
        CompletableFuture<Void> callBackCalled = new CompletableFuture<>();
        // issue a zkClient request.. 
        CompletableFuture.runAsync(() -> {
            try {
                curatorClient.getData().inBackground((client1, event) -> {
                    callBackCalled.complete(null);
                }).forPath("/test");
            } catch (Exception e) {
                Assert.fail("Failed while trying to submit a background request to curator");
            }
        });

        // verify that termination is started. We will first make sure curator calls the callback before we let the 
        // ControllerServiceStarter to shutdown completely. This simulates grpc behaviour where grpc waits until all 
        // outstanding calls are complete before shutting down. 
        signalShutdownStarted.join();
        callBackCalled.join();
        
        // Now that callback has been called we can signal the shutdown of ControllerServiceStarter to complete. 
        waitingForShutdownSignal.complete(null);
        
        // Now, that session has expired, lets wait for starter to start again.
        try {
            controllerServiceMain.awaitServicePausing().awaitTerminated();
        } catch (IllegalStateException e) {
            log.error("Failed waiting for controllerServiceStarter termination", e);
            Assert.fail("Failed waiting for controllerServiceStarter termination");
        }

        try {
            controllerServiceMain.awaitServiceStarting().awaitRunning();
        } catch (IllegalStateException e) {
            log.error("Failed waiting for starter to get ready again", e);
            Assert.fail("Failed waiting for controllerServiceStarter to get ready again");
        }

        // assert that previous curator client has indeed shutdown. 
        assertEquals(curatorClient.getState(), CuratorFrameworkState.STOPPED);

        // assert that a new curator client is added to the queue and it is in started state
        assertEquals(1, clientQueue.size());
        assertEquals(((CuratorFramework) clientQueue.peek().getClient()).getState(), CuratorFrameworkState.STARTED);

        controllerServiceMain.stopAsync();

        try {
            controllerServiceMain.awaitServicePausing().awaitTerminated();
        } catch (IllegalStateException e) {
            log.error("Failed waiting for controllerServiceStarter termination", e);
            Assert.fail("Failed waiting for controllerServiceStarter termination");
        }

        try {
            controllerServiceMain.awaitTerminated();
        } catch (IllegalStateException e) {
            log.error("Failed waiting for termination of controllerServiceMain", e);
            Assert.fail("Failed waiting for termination of controllerServiceMain");
        }
    }
}
