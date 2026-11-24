package io.github.madaai.idlestow.app;

import io.github.madaai.idlestow.app.util.Coalescer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoalescerTest {
    @Test
    void burstsAreMergedIntoOneRun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        Coalescer c = new Coalescer(80, Runnable::run, () -> {
            runs.incrementAndGet();
            first.countDown();
        });
        for (int i = 0; i < 100; i++) c.request();
        assertTrue(first.await(2, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(1, runs.get());
        c.request();
        Thread.sleep(300);
        assertEquals(2, runs.get());
    }
}
