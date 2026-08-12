package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteInitializationPolicyTest {
    @Test
    public void readyAccountCanInitializeRoute() {
        assertTrue(RouteInitializationPolicy.allowed(true, 7L));
    }

    @Test
    public void loggingOutOldAccountCannotReinitializeRoute() {
        assertFalse(RouteInitializationPolicy.allowed(false, 7L));
    }

    @Test
    public void readyWithoutResolvedAccountCannotInitializeRoute() {
        assertFalse(RouteInitializationPolicy.allowed(true, 0L));
    }
}