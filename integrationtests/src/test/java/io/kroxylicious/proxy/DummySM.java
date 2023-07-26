/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.security.Permission;
import java.util.Arrays;

import static java.lang.Thread.currentThread;

public class DummySM extends SecurityManager {

    @Override
    public void checkExit(int status) {
        Thread thread = currentThread();
        System.out.println("Hey! " + thread.getName() + " is exiting: " + Arrays.toString(thread.getStackTrace()));
        super.checkExit(status);
    }

    @Override
    public void checkPermission(Permission perm) {
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
    }

    @Override
    public void checkCreateClassLoader() {
    }

    @Override
    public void checkAccess(Thread t) {
    }

    @Override
    public void checkAccess(ThreadGroup g) {
    }

    @Override
    public void checkExec(String cmd) {
    }

    @Override
    public void checkLink(String lib) {
    }

    @Override
    public void checkRead(FileDescriptor fd) {
    }

    @Override
    public void checkRead(String file) {
    }

    @Override
    public void checkRead(String file, Object context) {
    }

    @Override
    public void checkWrite(FileDescriptor fd) {
    }

    @Override
    public void checkWrite(String file) {
    }

    @Override
    public void checkDelete(String file) {
    }

    @Override
    public void checkConnect(String host, int port) {
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
    }

    @Override
    public void checkListen(int port) {
    }

    @Override
    public void checkAccept(String host, int port) {
    }

    @Override
    public void checkMulticast(InetAddress maddr) {
    }

    @Override
    public void checkMulticast(InetAddress maddr, byte ttl) {
    }

    @Override
    public void checkPropertiesAccess() {
    }

    @Override
    public void checkPropertyAccess(String key) {
    }

    @Override
    public void checkPrintJobAccess() {
    }

    @Override
    public void checkPackageAccess(String pkg) {
    }

    @Override
    public void checkPackageDefinition(String pkg) {
    }

    @Override
    public void checkSetFactory() {
    }

    @Override
    public void checkSecurityAccess(String target) {
    }

}
