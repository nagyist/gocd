/*
 * Copyright Thoughtworks, Inc.
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
package com.thoughtworks.go.util;

import java.io.Serializable;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;

public class SystemTimeClock implements Clock, Serializable {
    @Override
    public Instant currentTime() {
        return Instant.now();
    }

    @Override
    public Date currentUtilDate() {
        return new Date(currentTimeMillis());
    }

    @Override
    public Timestamp currentSqlTimestamp() {
        return new Timestamp(currentTimeMillis());
    }

    @Override
    public LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void sleepForSeconds(long seconds) throws InterruptedException {
        sleepForMillis(seconds * 1000);
    }

    @Override
    public void sleepForMillis(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @Override
    public Instant timeoutTime(Timeout timeout) {
        return timeoutTime(timeout.inMillis());
    }

    @Override
    public Instant timeoutTime(long timeoutInMillis) {
        return currentTime().plusMillis(timeoutInMillis);
    }
}
