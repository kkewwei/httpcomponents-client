/*
 * ====================================================================
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
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.impl.cookie;

import java.time.Instant;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.cookie.CommonCookieAttributeHandler;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.MalformedCookieException;
import org.apache.hc.client5.http.cookie.SetCookie;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TextUtils;
import org.apache.hc.core5.util.Tokenizer;

/**
 * Cookie {@code expires} attribute handler conformant to the more relaxed interpretation
 * of HTTP state management.
 *
 * @since 4.4
 */
@Contract(threading = ThreadingBehavior.STATELESS)
public class LaxExpiresHandler extends AbstractCookieAttributeHandler implements CommonCookieAttributeHandler {

    private static final BitSet DELIMS;
    static {
        final BitSet bitSet = new BitSet();
        bitSet.set(0x9);
        for (int b = 0x20; b <= 0x2f; b++) {
            bitSet.set(b);
        }
        for (int b = 0x3b; b <= 0x40; b++) {
            bitSet.set(b);
        }
        for (int b = 0x5b; b <= 0x60; b++) {
            bitSet.set(b);
        }
        for (int b = 0x7b; b <= 0x7e; b++) {
            bitSet.set(b);
        }
        DELIMS = bitSet;
    }
    private static final Map<String, Month> MONTHS;
    static {
        final ConcurrentHashMap<String, Month> map = new ConcurrentHashMap<>(12);
        map.put("jan", Month.JANUARY);
        map.put("feb", Month.FEBRUARY);
        map.put("mar", Month.MARCH);
        map.put("apr", Month.APRIL);
        map.put("may", Month.MAY);
        map.put("jun", Month.JUNE);
        map.put("jul", Month.JULY);
        map.put("aug", Month.AUGUST);
        map.put("sep", Month.SEPTEMBER);
        map.put("oct", Month.OCTOBER);
        map.put("nov", Month.NOVEMBER);
        map.put("dec", Month.DECEMBER);
        MONTHS = map;
    }

    private final static Pattern TIME_PATTERN = Pattern.compile(
            "^([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})([^0-9].*)?$");
    private final static Pattern DAY_OF_MONTH_PATTERN = Pattern.compile(
            "^([0-9]{1,2})([^0-9].*)?$");
    private final static Pattern MONTH_PATTERN = Pattern.compile(
            "^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(.*)?$", Pattern.CASE_INSENSITIVE);
    private final static Pattern YEAR_PATTERN = Pattern.compile(
            "^([0-9]{2,4})([^0-9].*)?$");

    public LaxExpiresHandler() {
        super();
    }

    @Override
    public void parse(final SetCookie cookie, final String value) throws MalformedCookieException {
        Args.notNull(cookie, "Cookie");
        if (TextUtils.isBlank(value)) {
            return;
        }
        final Tokenizer.Cursor cursor = new Tokenizer.Cursor(0, value.length());
        final StringBuilder content = new StringBuilder();

        int second = 0, minute = 0, hour = 0, day = 0, year = 0;
        Month month = Month.JANUARY;
        boolean foundTime = false, foundDayOfMonth = false, foundMonth = false, foundYear = false;
        try {
            while (!cursor.atEnd()) {
                skipDelims(value, cursor);
                content.setLength(0);
                copyContent(value, cursor, content);

                if (content.length() == 0) {
                    break;
                }
                if (!foundTime) {
                    final Matcher matcher = TIME_PATTERN.matcher(content);
                    if (matcher.matches()) {
                        foundTime = true;
                        hour = Integer.parseInt(matcher.group(1));
                        minute = Integer.parseInt(matcher.group(2));
                        second =Integer.parseInt(matcher.group(3));
                        continue;
                    }
                }
                if (!foundDayOfMonth) {
                    final Matcher matcher = DAY_OF_MONTH_PATTERN.matcher(content);
                    if (matcher.matches()) {
                        foundDayOfMonth = true;
                        day = Integer.parseInt(matcher.group(1));
                        continue;
                    }
                }
                if (!foundMonth) {
                    final Matcher matcher = MONTH_PATTERN.matcher(content);
                    if (matcher.matches()) {
                        foundMonth = true;
                        month = MONTHS.get(matcher.group(1).toLowerCase(Locale.ROOT));
                        continue;
                    }
                }
                if (!foundYear) {
                    final Matcher matcher = YEAR_PATTERN.matcher(content);
                    if (matcher.matches()) {
                        foundYear = true;
                        year = Integer.parseInt(matcher.group(1));
                        continue;
                    }
                }
            }
        } catch (final NumberFormatException ignore) {
            throw new MalformedCookieException("Invalid 'expires' attribute: " + value);
        }
        if (!foundTime || !foundDayOfMonth || !foundMonth || !foundYear) {
            throw new MalformedCookieException("Invalid 'expires' attribute: " + value);
        }
        if (year >= 70 && year <= 99) {
            year = 1900 + year;
        }
        if (year >= 0 && year <= 69) {
            year = 2000 + year;
        }
        if (day < 1 || day > 31 || year < 1601 || hour > 23 || minute > 59 || second > 59) {
            throw new MalformedCookieException("Invalid 'expires' attribute: " + value);
        }

        final Instant expiryDate = ZonedDateTime.of(year, month.getValue(), day, hour, minute, second, 0,
                ZoneId.of("UTC")).toInstant();
        cookie.setExpiryDate(DateUtils.toDate(expiryDate));
    }

    private void skipDelims(final CharSequence buf, final Tokenizer.Cursor cursor) {
        int pos = cursor.getPos();
        final int indexFrom = cursor.getPos();
        final int indexTo = cursor.getUpperBound();
        for (int i = indexFrom; i < indexTo; i++) {
            final char current = buf.charAt(i);
            if (DELIMS.get(current)) {
                pos++;
            } else {
                break;
            }
        }
        cursor.updatePos(pos);
    }

    private void copyContent(final CharSequence buf, final Tokenizer.Cursor cursor, final StringBuilder dst) {
        int pos = cursor.getPos();
        final int indexFrom = cursor.getPos();
        final int indexTo = cursor.getUpperBound();
        for (int i = indexFrom; i < indexTo; i++) {
            final char current = buf.charAt(i);
            if (DELIMS.get(current)) {
                break;
            }
            pos++;
            dst.append(current);
        }
        cursor.updatePos(pos);
    }

    @Override
    public String getAttributeName() {
        return Cookie.EXPIRES_ATTR;
    }

}
