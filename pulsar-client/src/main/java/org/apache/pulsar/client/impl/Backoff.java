/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import java.util.Random;
import java.util.concurrent.TimeUnit;

// All variables are in TimeUnit millis by default
public class Backoff {
    private static final long DEFAULT_INTERVAL_IN_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long MAX_BACKOFF_INTERVAL_NANOSECONDS = TimeUnit.SECONDS.toNanos(30);
    private final long initial;
    private final long max;
    private long next;
    private long mandatoryStop;
    long firstBackoffTimeInMillis;
    private boolean mandatoryStopMade = false;

    private static final Random random = new Random();

    public Backoff(long initial, TimeUnit unitInitial, long max, TimeUnit unitMax, long mandatoryStop,
            TimeUnit unitMandatoryStop) {
        this.initial = unitInitial.toMillis(initial);
        this.max = unitMax.toMillis(max);
        this.next = this.initial;
        this.mandatoryStop = unitMandatoryStop.toMillis(mandatoryStop);
    }

    public long next() {
        long current = this.next;
        if (current < max) {
            this.next = Math.min(this.next * 2, this.max);
        }
        
        // Check for mandatory stop
        if (!mandatoryStopMade) {
            long now = System.currentTimeMillis();
            long timeElapsedSinceFirstBackoff = 0;
            if (initial == current) {
                firstBackoffTimeInMillis = now;
            } else {
                timeElapsedSinceFirstBackoff = now - firstBackoffTimeInMillis;
            }
    
            if (timeElapsedSinceFirstBackoff + current > mandatoryStop) {
                current = Math.max(initial, mandatoryStop - timeElapsedSinceFirstBackoff);
                mandatoryStopMade = true;
            }
        }
        
        // Randomly decrease the timeout up to 10% to avoid simultaneous retries        
        // If current < 10 then current/10 < 1 and we get an exception from Random saying "Bound must be positive"
        if (current > 10) {
            current -= random.nextInt((int) current / 10);
        }
        return Math.max(initial, current);
    }

    public void reduceToHalf() {
        if (next > initial) {
            this.next = Math.max(this.next / 2, this.initial);
        }
    }

    public void reset() {
        this.next = this.initial;
        this.mandatoryStopMade = false;
    }

    public static boolean shouldBackoff(long initialTimestamp, TimeUnit unitInitial, int failedAttempts) {
        long initialTimestampInNano = unitInitial.toNanos(initialTimestamp);
        long currentTime = System.nanoTime();
        long interval = DEFAULT_INTERVAL_IN_NANOSECONDS;
        for (int i = 1; i < failedAttempts; i++) {
            interval = interval * 2;
            if (interval > MAX_BACKOFF_INTERVAL_NANOSECONDS) {
                interval = MAX_BACKOFF_INTERVAL_NANOSECONDS;
                break;
            }
        }

        // if the current time is less than the time at which next retry should occur, we should backoff
        return currentTime < (initialTimestampInNano + interval);
    }
}